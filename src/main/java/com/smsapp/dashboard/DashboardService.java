package com.smsapp.dashboard;

import com.smsapp.school.SchoolRepository;
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

    private final SchoolRepository schoolRepository;
    private final UserRepository userRepository;

    public DashboardService(SchoolRepository schoolRepository, UserRepository userRepository) {
        this.schoolRepository = schoolRepository;
        this.userRepository = userRepository;
    }

    /**
     * Builds the summary for the authenticated caller. Runs in a transaction so the
     * tenant session variable is set (RLS), and additionally filters every count by
     * {@code tenantId} at the query level — defense in depth per plan section 1.
     */
    @Transactional(readOnly = true)
    public DashboardSummary summaryFor(UUID tenantId, String userId, List<String> roles) {
        if (roles.contains(Roles.SCHOOL_ADMIN)) {
            DashboardSummary.Counts counts = new DashboardSummary.Counts(
                    schoolRepository.countByTenantId(tenantId),
                    userRepository.countByTenantId(tenantId));
            return DashboardSummary.forSchoolAdmin(userId, tenantId.toString(), roles, counts);
        }

        return DashboardSummary.placeholder(userId, tenantId.toString(), roles, PLACEHOLDER_NOTE);
    }
}
