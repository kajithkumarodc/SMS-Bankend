package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.StaffAttendanceDtos.AttendanceEntry;
import com.smsapp.staff.StaffAttendanceDtos.RosterRow;
import com.smsapp.staff.StaffDirectoryDtos.LookupOption;
import com.smsapp.staff.StaffDirectoryDtos.StaffCardResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Human Resource > Staff Attendance: the active staff of one role for a day, with their saved marks, and saving
 * marks for a day. A day holds one mark per staff member; saving again changes it. Marks cannot be made for a
 * future date.
 */
@Service
public class StaffAttendanceService {

    static final String SOURCE_MANUAL = "MANUAL";

    private final StaffAttendanceRepository attendanceRepository;
    private final StaffProfileRepository profileRepository;
    private final StaffDirectoryService directoryService;
    private final AuditService auditService;

    public StaffAttendanceService(StaffAttendanceRepository attendanceRepository, StaffProfileRepository profileRepository,
                                  StaffDirectoryService directoryService, AuditService auditService) {
        this.attendanceRepository = attendanceRepository;
        this.profileRepository = profileRepository;
        this.directoryService = directoryService;
        this.auditService = auditService;
    }

    /** Roles that staff can hold -- the page's Role choices. */
    @Transactional(readOnly = true)
    public List<LookupOption> roles() {
        return directoryService.options(true).roles();
    }

    /** The active staff of {@code roleId}, ordered by Staff ID, each with the mark saved for {@code date} (if any). */
    @Transactional(readOnly = true)
    public List<RosterRow> roster(UUID roleId, LocalDate date) {
        List<StaffCardResponse> staff = directoryService.list(roleId, null, StaffDirectoryService.ACTIVE);
        Map<UUID, StaffAttendance> saved = staff.isEmpty() ? Map.of()
                : attendanceRepository.findByAttendanceDateAndStaffProfileIdIn(date,
                        staff.stream().map(StaffCardResponse::id).toList()).stream()
                .collect(Collectors.toMap(StaffAttendance::getStaffProfileId, a -> a));
        return staff.stream().map(s -> {
            StaffAttendance mark = saved.get(s.id());
            return new RosterRow(s.id(), s.staffId(), s.fullName(), s.roleName(),
                    mark == null ? null : mark.getStatus(), mark == null ? null : mark.getAttendanceDate(),
                    mark == null ? SOURCE_MANUAL : mark.getSource(), mark == null ? null : mark.getEntryTime(),
                    mark == null ? null : mark.getExitTime(), mark == null ? null : mark.getNote());
        }).toList();
    }

    /**
     * Saves the marks for {@code date}, replacing any earlier mark of the same staff member for that day.
     *
     * @return how many marks were saved
     * @throws ApiException 400 for a future date, a duplicated staff member, an exit time that is not after the
     *         entry time, or a staff member who is inactive; 404 if a staff member does not exist.
     */
    @Transactional
    public int save(LocalDate date, List<AttendanceEntry> entries, UUID markedByUserId) {
        if (date.isAfter(LocalDate.now())) {
            throw new ApiException("Attendance cannot be marked for a future date", HttpStatus.BAD_REQUEST);
        }
        Set<UUID> ids = new HashSet<>();
        for (AttendanceEntry entry : entries) {
            if (!ids.add(entry.staffProfileId())) {
                throw new ApiException("A staff member appears more than once", HttpStatus.BAD_REQUEST);
            }
            if (entry.entryTime() != null && entry.exitTime() != null && !entry.exitTime().isAfter(entry.entryTime())) {
                throw new ApiException("Exit time must be after the entry time", HttpStatus.BAD_REQUEST);
            }
        }
        Map<UUID, StaffProfile> profiles = profileRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(StaffProfile::getId, p -> p));
        for (UUID id : ids) {
            StaffProfile profile = profiles.get(id);
            if (profile == null) {
                throw new ApiException("Staff member not found", HttpStatus.NOT_FOUND);
            }
            if (!StaffDirectoryService.ACTIVE.equals(profile.getStatus())) {
                throw new ApiException(profile.getEmployeeCode() + " is not an active staff member", HttpStatus.BAD_REQUEST);
            }
        }
        Map<UUID, StaffAttendance> existing = new HashMap<>();
        attendanceRepository.findByAttendanceDateAndStaffProfileIdIn(date, ids)
                .forEach(a -> existing.put(a.getStaffProfileId(), a));
        for (AttendanceEntry entry : entries) {
            StaffAttendance mark = existing.getOrDefault(entry.staffProfileId(), new StaffAttendance());
            mark.setStaffProfileId(entry.staffProfileId());
            mark.setAttendanceDate(date);
            mark.setStatus(StaffAttendanceStatus.parse(entry.status()).orElseThrow().name());
            mark.setSource(SOURCE_MANUAL);
            mark.setEntryTime(entry.entryTime());
            mark.setExitTime(entry.exitTime());
            mark.setNote(entry.note() == null || entry.note().isBlank() ? null : entry.note().trim());
            mark.setMarkedByUserId(markedByUserId);
            attendanceRepository.save(mark);
        }
        auditService.log(AuditActions.STAFF_ATTENDANCE_MARKED, AuditActions.STAFF_ATTENDANCE, null,
                Map.of("date", date.toString(), "count", entries.size()));
        return entries.size();
    }
}
