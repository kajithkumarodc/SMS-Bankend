package com.smsapp.dashboard;

import com.smsapp.announcement.Announcement;
import com.smsapp.announcement.AnnouncementRepository;
import com.smsapp.attendance.AttendanceRepository;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AttendanceRepository attendanceRepository;

    @Mock
    private AnnouncementRepository announcementRepository;

    private DashboardService service() {
        return new DashboardService(schoolRepository, userRepository, studentRepository, attendanceRepository,
                announcementRepository);
    }

    private Student student(UUID id, String fullName, UUID studentUserId, UUID guardianUserId) {
        Student student = new Student();
        student.setId(id);
        student.setFullName(fullName);
        student.setAdmissionNumber("ADM-" + fullName);
        student.setStatus("ACTIVE");
        student.setStudentUserId(studentUserId);
        student.setGuardianUserId(guardianUserId);
        return student;
    }

    @Test
    void schoolAdminGetsRealCounts() {
        when(schoolRepository.count()).thenReturn(4L);
        when(userRepository.count()).thenReturn(12L);

        DashboardSummary summary = service().summaryFor("user-1", List.of(Roles.SCHOOL_ADMIN));

        assertThat(summary.placeholder()).isFalse();
        assertThat(summary.counts()).isEqualTo(new DashboardSummary.Counts(4L, 12L));
        verify(schoolRepository).count();
        verify(userRepository).count();
    }

    @Test
    void nonAdminNonPortalRoleGetsPlaceholder() {
        DashboardSummary summary = service().summaryFor("user-2", List.of(Roles.TEACHER));

        assertThat(summary.placeholder()).isTrue();
        assertThat(summary.counts()).isNull();
        assertThat(summary.note()).isNotBlank();
    }

    @Test
    void studentWithLinkedRecordGetsOwnInfoAndAttendanceSummary() {
        UUID userId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findByStudentUserId(userId))
                .thenReturn(Optional.of(student(studentId, "Asha", userId, null)));
        when(attendanceRepository.tallyByStatus(studentId)).thenReturn(List.of(
                tally("PRESENT", 8L), tally("ABSENT", 2L), tally("LATE", 1L)));

        DashboardSummary summary = service().summaryFor(userId.toString(), List.of(Roles.STUDENT));

        assertThat(summary.placeholder()).isFalse();
        assertThat(summary.student().fullName()).isEqualTo("Asha");
        assertThat(summary.attendance()).isEqualTo(new DashboardSummary.AttendanceSummary(8L, 2L, 1L, 11L));
        assertThat(summary.children()).isNull();
    }

    @Test
    void studentWithoutLinkedRecordGetsPlaceholderNotAnError() {
        UUID userId = UUID.randomUUID();
        when(studentRepository.findByStudentUserId(userId)).thenReturn(Optional.empty());

        DashboardSummary summary = service().summaryFor(userId.toString(), List.of(Roles.STUDENT));

        assertThat(summary.placeholder()).isTrue();
        assertThat(summary.student()).isNull();
        assertThat(summary.note()).isNotBlank();
    }

    @Test
    void parentGetsChildrenBasicInfo() {
        UUID userId = UUID.randomUUID();
        when(studentRepository.findByGuardianUserIdOrderByFullName(userId)).thenReturn(List.of(
                student(UUID.randomUUID(), "Kiran", null, userId),
                student(UUID.randomUUID(), "Meera", null, userId)));

        DashboardSummary summary = service().summaryFor(userId.toString(), List.of(Roles.PARENT));

        assertThat(summary.placeholder()).isFalse();
        assertThat(summary.children()).extracting(DashboardSummary.StudentInfo::fullName)
                .containsExactly("Kiran", "Meera");
        assertThat(summary.student()).isNull();
    }

    @Test
    void recentAnnouncementsAreIncludedForEveryRole() {
        Announcement a = new Announcement();
        a.setId(UUID.randomUUID());
        a.setTitle("Sports day moved to Friday");
        a.setBody("Details to follow.");
        a.setCreatedAt(java.time.OffsetDateTime.now());
        when(announcementRepository.findTop3ByOrderByCreatedAtDesc()).thenReturn(List.of(a));

        for (String role : List.of(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.STUDENT, Roles.PARENT)) {
            DashboardSummary summary = service().summaryFor(UUID.randomUUID().toString(), List.of(role));
            assertThat(summary.announcements()).singleElement()
                    .extracting(DashboardSummary.AnnouncementSummary::title)
                    .isEqualTo("Sports day moved to Friday");
        }
    }

    private static AttendanceRepository.StatusTally tally(String status, long total) {
        return new AttendanceRepository.StatusTally() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }
}
