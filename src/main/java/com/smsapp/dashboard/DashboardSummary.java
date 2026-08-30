package com.smsapp.dashboard;

import java.util.List;

/**
 * Role-scoped dashboard payload.
 *
 * <p>For SCHOOL_ADMIN, {@code counts} holds real tenant-scoped totals and
 * {@code placeholder} is false. For every other role the real data sources
 * (classes, attendance, fees, ...) do not exist yet, so {@code placeholder}
 * is true, {@code counts} is null and {@code note} explains what is pending.
 */
public record DashboardSummary(
        String userId,
        String tenantId,
        List<String> roles,
        boolean placeholder,
        String note,
        Counts counts) {

    public record Counts(long schools, long users) {
    }

    static DashboardSummary forSchoolAdmin(String userId, String tenantId, List<String> roles, Counts counts) {
        return new DashboardSummary(userId, tenantId, roles, false, null, counts);
    }

    static DashboardSummary placeholder(String userId, String tenantId, List<String> roles, String note) {
        return new DashboardSummary(userId, tenantId, roles, true, note, null);
    }
}
