package com.smsapp.timetable;

import com.smsapp.academics.SectionRepository;
import com.smsapp.academics.Subject;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import com.smsapp.timetable.TimetableDtos.PeriodRequest;
import com.smsapp.timetable.TimetableDtos.PeriodResponse;
import com.smsapp.timetable.TimetableDtos.TimetableSaveRequest;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Academics > Class Timetable. A section's week is the periods of every subject group it belongs to. Saving replaces
 * the periods of one section and group. Within a section no two periods on a day may overlap, and a teacher can't
 * be in two places at the same time.
 */
@Service
public class TimetableService {

    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private final TimetableEntryRepository entryRepository;
    private final SubjectGroupService groupService;
    private final SectionRepository sectionRepository;
    private final SubjectRepository subjectRepository;
    private final StaffProfileRepository staffRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public TimetableService(TimetableEntryRepository entryRepository, SubjectGroupService groupService,
                            SectionRepository sectionRepository, SubjectRepository subjectRepository,
                            StaffProfileRepository staffRepository, UserRepository userRepository, AuditService auditService) {
        this.entryRepository = entryRepository;
        this.groupService = groupService;
        this.sectionRepository = sectionRepository;
        this.subjectRepository = subjectRepository;
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * A section's periods, by day and start time; {@code subjectGroupId} narrows to one group (the Create Timetable page).
     *
     * @throws ApiException 404 if the section does not exist.
     */
    @Transactional(readOnly = true)
    public List<PeriodResponse> periods(UUID sectionId, UUID subjectGroupId) {
        requireSection(sectionId);
        List<TimetableEntry> entries = subjectGroupId == null ? entryRepository.findBySectionIdOrderByDayOfWeekAscTimeFromAsc(sectionId)
                : entryRepository.findBySectionIdAndSubjectGroupIdOrderByDayOfWeekAscTimeFromAsc(sectionId, subjectGroupId);
        return toResponses(entries);
    }

    /** A teacher's periods across every section, by day and start time. @throws ApiException 404 if no such staff member. */
    @Transactional(readOnly = true)
    public List<PeriodResponse> teacherPeriods(UUID staffProfileId) {
        if (!staffRepository.existsById(staffProfileId)) {
            throw new ApiException("Staff member not found", HttpStatus.NOT_FOUND);
        }
        return toResponses(entryRepository.findByStaffProfileIdOrderByDayOfWeekAscTimeFromAsc(staffProfileId));
    }

    /**
     * Replaces the periods of a section for one subject group.
     *
     * @throws ApiException 404 if the section, group or a staff member doesn't exist; 400 if the group doesn't cover the
     *         section, a subject isn't in the group, or a period ends before it starts; 409 if two periods of the section
     *         overlap or a teacher is already teaching another section at that time.
     */
    @Transactional
    public List<PeriodResponse> save(TimetableSaveRequest request) {
        requireSection(request.sectionId());
        SubjectGroup group = groupService.require(request.subjectGroupId());
        if (!group.getSectionIds().contains(request.sectionId())) {
            throw new ApiException("This subject group does not cover that section", HttpStatus.BAD_REQUEST);
        }
        for (PeriodRequest p : request.periods()) {
            if (!group.getSubjectIds().contains(p.subjectId())) {
                throw new ApiException("A subject is not part of the subject group", HttpStatus.BAD_REQUEST);
            }
            if (!p.timeTo().isAfter(p.timeFrom())) {
                throw new ApiException(DAYS[p.dayOfWeek() - 1] + ": a period must end after it starts", HttpStatus.BAD_REQUEST);
            }
            if (p.staffProfileId() != null && !staffRepository.existsById(p.staffProfileId())) {
                throw new ApiException("Staff member not found", HttpStatus.NOT_FOUND);
            }
        }
        // Periods of the section's other groups stay; the new ones must not overlap them or each other.
        List<TimetableEntry> others = entryRepository.findBySectionIdOrderByDayOfWeekAscTimeFromAsc(request.sectionId()).stream()
                .filter(e -> !e.getSubjectGroupId().equals(request.subjectGroupId())).toList();
        List<PeriodRequest> mine = new ArrayList<>(request.periods());
        for (int i = 0; i < mine.size(); i++) {
            PeriodRequest a = mine.get(i);
            for (int j = i + 1; j < mine.size(); j++) {
                if (overlaps(a.dayOfWeek(), a.timeFrom(), a.timeTo(), mine.get(j).dayOfWeek(), mine.get(j).timeFrom(), mine.get(j).timeTo())) {
                    throw new ApiException(DAYS[a.dayOfWeek() - 1] + ": " + range(a.timeFrom(), a.timeTo()) + " overlaps "
                            + range(mine.get(j).timeFrom(), mine.get(j).timeTo()), HttpStatus.CONFLICT);
                }
            }
            for (TimetableEntry o : others) {
                if (overlaps(a.dayOfWeek(), a.timeFrom(), a.timeTo(), o.getDayOfWeek(), o.getTimeFrom(), o.getTimeTo())) {
                    throw new ApiException(DAYS[a.dayOfWeek() - 1] + ": " + range(a.timeFrom(), a.timeTo())
                            + " overlaps another period of this section (" + range(o.getTimeFrom(), o.getTimeTo()) + ")", HttpStatus.CONFLICT);
                }
            }
            if (a.staffProfileId() != null) {
                for (TimetableEntry t : entryRepository.findByStaffProfileIdAndDayOfWeek(a.staffProfileId(), a.dayOfWeek())) {
                    boolean sameSectionAndGroup = t.getSectionId().equals(request.sectionId()) && t.getSubjectGroupId().equals(request.subjectGroupId());
                    if (!sameSectionAndGroup && overlaps(a.dayOfWeek(), a.timeFrom(), a.timeTo(), t.getDayOfWeek(), t.getTimeFrom(), t.getTimeTo())) {
                        throw new ApiException(teacherName(a.staffProfileId()) + " is already teaching on " + DAYS[a.dayOfWeek() - 1] + ", "
                                + range(t.getTimeFrom(), t.getTimeTo()), HttpStatus.CONFLICT);
                    }
                }
            }
        }
        entryRepository.deleteBySectionIdAndSubjectGroupId(request.sectionId(), request.subjectGroupId());
        entryRepository.flush();
        List<TimetableEntry> saved = new ArrayList<>();
        for (PeriodRequest p : mine) {
            TimetableEntry entry = new TimetableEntry();
            entry.setSectionId(request.sectionId());
            entry.setSubjectGroupId(request.subjectGroupId());
            entry.setSubjectId(p.subjectId());
            entry.setDayOfWeek(p.dayOfWeek());
            entry.setTimeFrom(p.timeFrom());
            entry.setTimeTo(p.timeTo());
            entry.setStaffProfileId(p.staffProfileId());
            String room = p.roomNo() == null ? null : p.roomNo().trim();
            entry.setRoomNo(room == null || room.isEmpty() ? null : room);
            saved.add(entryRepository.save(entry));
        }
        auditService.log(AuditActions.TIMETABLE_SAVED, AuditActions.TIMETABLE, request.sectionId(),
                Map.of("subjectGroupId", request.subjectGroupId().toString(), "periods", saved.size()));
        saved.sort(Comparator.comparingInt(TimetableEntry::getDayOfWeek).thenComparing(TimetableEntry::getTimeFrom));
        return toResponses(saved);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private void requireSection(UUID sectionId) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
    }

    private static boolean overlaps(int dayA, LocalTime fromA, LocalTime toA, int dayB, LocalTime fromB, LocalTime toB) {
        return dayA == dayB && fromA.isBefore(toB) && fromB.isBefore(toA);
    }

    private static String range(LocalTime from, LocalTime to) {
        return CLOCK.format(from) + " - " + CLOCK.format(to);
    }

    private String teacherName(UUID staffProfileId) {
        return staffRepository.findById(staffProfileId).flatMap(p -> userRepository.findById(p.getUserId())).map(User::getFullName)
                .orElse("This teacher");
    }

    private List<PeriodResponse> toResponses(List<TimetableEntry> entries) {
        Map<UUID, Subject> subjects = subjectRepository.findAll().stream().collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<UUID, StaffProfile> staff = staffRepository.findAll().stream().collect(Collectors.toMap(StaffProfile::getId, Function.identity()));
        Map<UUID, String> names = userRepository.findAllById(staff.values().stream().map(StaffProfile::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        return entries.stream().map(e -> {
            Subject subject = subjects.get(e.getSubjectId());
            StaffProfile teacher = e.getStaffProfileId() == null ? null : staff.get(e.getStaffProfileId());
            return new PeriodResponse(e.getId(), e.getSectionId(), e.getSubjectGroupId(), e.getDayOfWeek(), e.getSubjectId(),
                    subject == null ? null : subject.getName(), subject == null ? null : subject.getCode(), e.getTimeFrom(), e.getTimeTo(),
                    e.getStaffProfileId(), teacher == null ? null : names.get(teacher.getUserId()), teacher == null ? null : teacher.getEmployeeCode(),
                    e.getRoomNo());
        }).toList();
    }
}
