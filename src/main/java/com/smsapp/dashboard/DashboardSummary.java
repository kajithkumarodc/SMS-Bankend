package com.smsapp.dashboard;

import java.util.List;

/**
 * Role-scoped dashboard payload.
 *
 * <p>Exactly one of the role-specific fields is populated per response:
 * <ul>
 *   <li>SCHOOL_ADMIN -> {@code counts} (real tenant-scoped totals), {@code placeholder} false</li>
 *   <li>STUDENT -> {@code student} + {@code attendance} (their own), {@code placeholder} false</li>
 *   <li>PARENT -> {@code children} (their linked students), {@code placeholder} false</li>
 *   <li>anyone else / not linked -> {@code placeholder} true with a {@code note}</li>
 * </ul>
 *
 * <p>{@code announcements} is populated for <em>every</em> role -- school-wide
 * messages are genuinely tenant-wide, seen the same by everyone.
 */
public record DashboardSummary(
        String userId,
        String tenantId,
        List<String> roles,
        boolean placeholder,
        String note,
        Counts counts,
        StudentInfo student,
        AttendanceSummary attendance,
        List<StudentInfo> children,
        List<AnnouncementSummary> announcements) {

    public record Counts(long schools, long users) {
    }

    /** Basic student info safe to show a student or parent -- no internal link ids. */
    public record StudentInfo(String id, String fullName, String admissionNumber, String status, String sectionId) {
    }

    public record AttendanceSummary(long present, long absent, long late, long total) {
    }

    /** A recent school-wide announcement, trimmed for the dashboard. */
    public record AnnouncementSummary(String id, String title, String body, String createdAt) {
    }

    static DashboardSummary forSchoolAdmin(String userId, String tenantId, List<String> roles, Counts counts,
                                           List<AnnouncementSummary> announcements) {
        return new DashboardSummary(userId, tenantId, roles, false, null, counts, null, null, null, announcements);
    }

    static DashboardSummary forStudent(String userId, String tenantId, List<String> roles,
                                       StudentInfo student, AttendanceSummary attendance,
                                       List<AnnouncementSummary> announcements) {
        return new DashboardSummary(userId, tenantId, roles, false, null, null, student, attendance, null,
                announcements);
    }

    static DashboardSummary forParent(String userId, String tenantId, List<String> roles,
                                      List<StudentInfo> children, List<AnnouncementSummary> announcements) {
        return new DashboardSummary(userId, tenantId, roles, false, null, null, null, null, children, announcements);
    }

    static DashboardSummary placeholder(String userId, String tenantId, List<String> roles, String note,
                                        List<AnnouncementSummary> announcements) {
        return new DashboardSummary(userId, tenantId, roles, true, note, null, null, null, null, announcements);
    }
}
