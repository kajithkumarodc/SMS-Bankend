package com.smsapp.hostel;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.hostel.HostelDtos.CreateBlockRequest;
import com.smsapp.hostel.HostelDtos.CreateRoomRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostelServiceTest {

    @Mock
    private HostelBlockRepository blockRepository;

    @Mock
    private HostelRoomRepository roomRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private HostelService service() {
        return new HostelService(blockRepository, roomRepository, studentRepository, auditService);
    }

    private final UUID blockId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private HostelRoom room(int capacity) {
        HostelRoom room = new HostelRoom();
        room.setId(roomId);
        room.setBlockId(blockId);
        room.setRoomNumber("A-101");
        room.setCapacity(capacity);
        return room;
    }

    private HostelBlock block() {
        HostelBlock block = new HostelBlock();
        block.setId(blockId);
        block.setName("Block A");
        return block;
    }

    private Student student(UUID id, String name) {
        Student s = new Student();
        s.setId(id);
        s.setFullName(name);
        return s;
    }

    // --- blocks -----------------------------------------------

    @Test
    void createBlockTrimsTheNameAndAudits() {
        when(blockRepository.save(any(HostelBlock.class))).thenAnswer(inv -> {
            HostelBlock b = inv.getArgument(0);
            b.setId(blockId);
            return b;
        });

        HostelBlock created = service().createBlock(new CreateBlockRequest("  Block A  "));

        assertThat(created.getName()).isEqualTo("Block A");
        verify(auditService).log(eq(AuditActions.HOSTEL_BLOCK_CREATED), eq(AuditActions.HOSTEL_BLOCK),
                eq(blockId), anyMap());
    }

    // --- rooms ------------------------------------------------

    @Test
    void addRoomStoresTheFieldsAndAudits() {
        when(blockRepository.existsById(blockId)).thenReturn(true);
        when(roomRepository.existsByBlockIdAndRoomNumber(blockId, "A-101")).thenReturn(false);
        when(roomRepository.saveAndFlush(any(HostelRoom.class))).thenAnswer(inv -> {
            HostelRoom r = inv.getArgument(0);
            r.setId(roomId);
            return r;
        });

        HostelRoom created = service().addRoom(blockId, new CreateRoomRequest("  A-101 ", 3));

        assertThat(created.getBlockId()).isEqualTo(blockId);
        assertThat(created.getRoomNumber()).isEqualTo("A-101");
        assertThat(created.getCapacity()).isEqualTo(3);
        verify(auditService).log(eq(AuditActions.HOSTEL_ROOM_ADDED), eq(AuditActions.HOSTEL_ROOM), eq(roomId), anyMap());
    }

    @Test
    void addRoomToANonexistentBlockReturns404() {
        when(blockRepository.existsById(blockId)).thenReturn(false);

        assertThatThrownBy(() -> service().addRoom(blockId, new CreateRoomRequest("A-101", 3)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(roomRepository, never()).saveAndFlush(any());
    }

    @Test
    void addRoomWithADuplicateNumberInTheBlockReturns409() {
        when(blockRepository.existsById(blockId)).thenReturn(true);
        when(roomRepository.existsByBlockIdAndRoomNumber(blockId, "A-101")).thenReturn(true);

        assertThatThrownBy(() -> service().addRoom(blockId, new CreateRoomRequest("A-101", 3)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(roomRepository, never()).saveAndFlush(any());
    }

    @Test
    void addRoomTranslatesAConcurrentInsertRaceIntoAClean409() {
        when(blockRepository.existsById(blockId)).thenReturn(true);
        when(roomRepository.existsByBlockIdAndRoomNumber(blockId, "A-101")).thenReturn(false);
        when(roomRepository.saveAndFlush(any(HostelRoom.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().addRoom(blockId, new CreateRoomRequest("A-101", 3)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listRoomsRejectsANonexistentBlockWith404() {
        when(blockRepository.existsById(blockId)).thenReturn(false);

        assertThatThrownBy(() -> service().listRooms(blockId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(roomRepository, never()).findRoomsWithOccupancy(any());
    }

    // --- allocation -----------------------------------------

    @Test
    void allocateStudentRoomSetsTheRoomAndAudits() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student(studentId, "Anaya")));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(2)));
        when(studentRepository.countByHostelRoomId(roomId)).thenReturn(1L);
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student saved = service().allocateStudentRoom(studentId, roomId);

        assertThat(saved.getHostelRoomId()).isEqualTo(roomId);
        verify(auditService).log(eq(AuditActions.HOSTEL_ROOM_ALLOCATED), eq(AuditActions.STUDENT),
                eq(studentId), anyMap());
    }

    @Test
    void allocateStudentRoomToAFullRoomReturns400() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student(studentId, "Anaya")));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(2)));
        when(studentRepository.countByHostelRoomId(roomId)).thenReturn(2L);

        assertThatThrownBy(() -> service().allocateStudentRoom(studentId, roomId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void reallocatingAStudentToTheRoomTheyAreAlreadyInSkipsTheCapacityCheck() {
        Student already = student(studentId, "Anaya");
        already.setHostelRoomId(roomId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(already));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        service().allocateStudentRoom(studentId, roomId);

        verify(roomRepository, never()).findById(any());
        verify(studentRepository, never()).countByHostelRoomId(any());
    }

    @Test
    void deallocatingWithANullRoomSkipsAnyRoomCheck() {
        Student already = student(studentId, "Anaya");
        already.setHostelRoomId(roomId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(already));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student saved = service().allocateStudentRoom(studentId, null);

        assertThat(saved.getHostelRoomId()).isNull();
        verify(roomRepository, never()).findById(any());
    }

    @Test
    void allocateStudentRoomWithANonexistentStudentReturns404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().allocateStudentRoom(studentId, roomId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void allocateStudentRoomWithANonexistentRoomReturns404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student(studentId, "Anaya")));
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().allocateStudentRoom(studentId, roomId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    // --- room roster ---------------------------------------

    @Test
    void studentsInRoomRejectsANonexistentRoomWith404() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().studentsInRoom(blockId, roomId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void studentsInRoomRejectsARoomThatIsNotInTheGivenBlockWith404() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(3)));

        assertThatThrownBy(() -> service().studentsInRoom(UUID.randomUUID(), roomId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).findByHostelRoomIdOrderByFullName(any());
    }

    // --- allocation view -----------------------------------

    @Test
    void allocationForRoomIs404WhenTheStudentHasNoRoom() {
        assertThatThrownBy(() -> service().allocationForRoom(studentId, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void allocationForRoomBuildsBlockRoomAndRoommatesExcludingSelf() {
        UUID roommateId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(3)));
        when(blockRepository.findById(blockId)).thenReturn(Optional.of(block()));
        when(studentRepository.findByHostelRoomIdOrderByFullName(roomId))
                .thenReturn(List.of(student(studentId, "Anaya"), student(roommateId, "Charu")));

        HostelAllocation allocation = service().allocationForRoom(studentId, roomId);

        assertThat(allocation.blockName()).isEqualTo("Block A");
        assertThat(allocation.roomNumber()).isEqualTo("A-101");
        assertThat(allocation.capacity()).isEqualTo(3);
        assertThat(allocation.roommates()).containsExactly("Charu");
    }
}
