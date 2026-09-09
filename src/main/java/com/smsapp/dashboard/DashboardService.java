package com.smsapp.dashboard;

import com.smsapp.announcement.Announcement;
import com.smsapp.announcement.AnnouncementRepository;
import com.smsapp.attendance.AttendanceRepository;
import com.smsapp.attendance.AttendanceStatus;
import com.smsapp.dashboard.DashboardSummary.AnnouncementSummary;
import com.smsapp.dashboard.DashboardSummary.AttendanceSummary;
import com.smsapp.dashboard.DashboardSummary.StudentInfo;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DashboardService {

    private static final String PLACEHOLDER_NOTE =
            "Role-specific dashboard data is not available yet. Widgets for classes, attendance, fees "
            + "and other modules will be added here as those modules are built.";

    private static final String STUDENT_NOT_LINKED_NOTE =
            "Your account is not linked to a student record yet. Ask your school office to connect it.";

    private final SchoolRepository schoolRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final AttendanceRepository attendanceRepository;
    private final AnnouncementRepository announcementRepository;

    public DashboardService(SchoolRepository schoolRepository, UserRepository userRepository,
                            StudentRepository studentRepository, AttendanceRepository attendanceRepository,
                            AnnouncementRepository announcementRepository) {
        this.schoolRepository = schoolRepository;
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.attendanceRepository = attendanceRepository;
        this.announcementRepository = announcementRepository;
    }

    /**
     * Builds the summary for the authenticated caller. Runs in a transaction so the
     * tenant session variable is set (RLS), and additionally filters every query by
     * {@code tenantId} -- defense in depth per plan section 1.
     */
    @Transactional(readOnly = true)
    public DashboardSummary summaryFor(UUID tenantId, String userId, List<String> roles) {
        String tenant = tenantId.toString();
        // School-wide, seen the same by every role -- not ownership-scoped.
        List<AnnouncementSummary> announcements = recentAnnouncements(tenantId);

        if (roles.contains(Roles.SCHOOL_ADMIN)) {
            DashboardSummary.Counts counts = new DashboardSummary.Counts(
                    schoolRepository.countByTenantId(tenantId),
                    userRepository.countByTenantId(tenantId));
            return DashboardSummary.forSchoolAdmin(userId, tenant, roles, counts, announcements);
        }

        if (roles.contains(Roles.STUDENT)) {
            Student self = studentRepository
                    .findByTenantIdAndStudentUserId(tenantId, UUID.fromString(userId))
                    .orElse(null);
            if (self != null) {
                return DashboardSummary.forStudent(userId, tenant, roles, toInfo(self),
                        attendanceSummary(tenantId, self.getId()), announcements);
            }
            return DashboardSummary.placeholder(userId, tenant, roles, STUDENT_NOT_LINKED_NOTE, announcements);
        }

        if (roles.contains(Roles.PARENT)) {
            List<StudentInfo> children = studentRepository
                    .findByTenantIdAndGuardianUserIdOrderByFullName(tenantId, UUID.fromString(userId))
                    .stream().map(DashboardService::toInfo).toList();
            return DashboardSummary.forParent(userId, tenant, roles, children, announcements);
        }

        return DashboardSummary.placeholder(userId, tenant, roles, PLACEHOLDER_NOTE, announcements);
    }

    private List<AnnouncementSummary> recentAnnouncements(UUID tenantId) {
        return announcementRepository.findTop3ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(DashboardService::toSummary)
                .toList();
    }

    private static AnnouncementSummary toSummary(Announcement announcement) {
        return new AnnouncementSummary(
                announcement.getId().toString(),
                announcement.getTitle(),
                announcement.getBody(),
                announcement.getCreatedAt().toString());
    }

    private AttendanceSummary attendanceSummary(UUID tenantId, UUID studentId) {
        long present = 0;
        long absent = 0;
        long late = 0;
        for (AttendanceRepository.StatusTally tally : attendanceRepository.tallyByStatus(tenantId, studentId)) {
            switch (tally.getStatus()) {
                case AttendanceStatus.PRESENT -> present = tally.getTotal();
                case AttendanceStatus.ABSENT -> absent = tally.getTotal();
                case AttendanceStatus.LATE -> late = tally.getTotal();
                default -> { /* unknown status -- ignore */ }
            }
        }
        return new AttendanceSummary(present, absent, late, present + absent + late);
    }

    private static StudentInfo toInfo(Student student) {
        return new StudentInfo(
                student.getId().toString(),
                student.getFullName(),
                student.getAdmissionNumber(),
                student.getStatus(),
                student.getSectionId() == null ? null : student.getSectionId().toString());
    }
}
