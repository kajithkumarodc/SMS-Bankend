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
        List<StudentInfo> children) {

    public record Counts(long schools, long users) {
    }

    /** Basic student info safe to show a student or parent -- no internal link ids. */
    public record StudentInfo(String id, String fullName, String admissionNumber, String status, String sectionId) {
    }

    public record AttendanceSummary(long present, long absent, long late, long total) {
    }

    static DashboardSummary forSchoolAdmin(String userId, String tenantId, List<String> roles, Counts counts) {
        return new DashboardSummary(userId, tenantId, roles, false, null, counts, null, null, null);
    }

    static DashboardSummary forStudent(String userId, String tenantId, List<String> roles,
                                       StudentInfo student, AttendanceSummary attendance) {
        return new DashboardSummary(userId, tenantId, roles, false, null, null, student, attendance, null);
    }

    static DashboardSummary forParent(String userId, String tenantId, List<String> roles,
                                      List<StudentInfo> children) {
        return new DashboardSummary(userId, tenantId, roles, false, null, null, null, null, children);
    }

    static DashboardSummary placeholder(String userId, String tenantId, List<String> roles, String note) {
        return new DashboardSummary(userId, tenantId, roles, true, note, null, null, null, null);
    }
}
