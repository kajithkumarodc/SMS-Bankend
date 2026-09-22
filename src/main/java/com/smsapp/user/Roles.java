package com.smsapp.user;

/**
 * Well-known role identifiers. These constants keep role names out of scattered
 * string literals in business logic (see plan section 7.3).
 */
public final class Roles {

    public static final String SCHOOL_ADMIN = "SCHOOL_ADMIN";
    public static final String TEACHER = "TEACHER";
    public static final String STUDENT = "STUDENT";
    public static final String PARENT = "PARENT";

    // Added for RBAC Phase 1 (database-driven roles, see V22 migration). These
    // are plain rows in the `roles` table like every other role -- listed here
    // only where a role name needs to appear in Java (SpEL constants, seed
    // data), same convention as the original four.
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String PRINCIPAL = "PRINCIPAL";
    public static final String ACCOUNTANT = "ACCOUNTANT";
    public static final String LIBRARIAN = "LIBRARIAN";
    public static final String RECEPTIONIST = "RECEPTIONIST";

    /**
     * Ready-made {@code @PreAuthorize} SpEL expressions, so the authorization rule
     * for a role lives in one place rather than being re-spelled at every endpoint.
     * Compile-time constants, so they are valid in annotation values.
     */
    private static final String ROLE_OPEN = "hasRole('";
    private static final String CLOSE = "')";

    public static final String HAS_SCHOOL_ADMIN = ROLE_OPEN + SCHOOL_ADMIN + CLOSE;
    public static final String HAS_SCHOOL_ADMIN_OR_TEACHER =
            "hasAnyRole('" + SCHOOL_ADMIN + "', '" + TEACHER + CLOSE;
    public static final String HAS_SCHOOL_ADMIN_OR_PARENT =
            "hasAnyRole('" + SCHOOL_ADMIN + "', '" + PARENT + CLOSE;
    public static final String HAS_TEACHER = ROLE_OPEN + TEACHER + CLOSE;
    public static final String HAS_STUDENT = ROLE_OPEN + STUDENT + CLOSE;
    public static final String HAS_PARENT = ROLE_OPEN + PARENT + CLOSE;

    /**
     * Guards the new Users/Roles/Permissions/Academic-Years management endpoints
     * (RBAC Phase 1). Deliberately role-based, not permission-based: RBAC
     * management is itself the thing that configures permissions, so it must
     * stay reachable by a fixed role regardless of how permissions are seeded
     * -- avoids a bootstrapping paradox / accidental lockout.
     */
    public static final String HAS_ADMIN = "hasAnyRole('" + SCHOOL_ADMIN + "', '" + SUPER_ADMIN + CLOSE;

    /** Spring maps the JWT {@code roles} claim to {@code ROLE_*} authorities. */
    public static final String ROLE_SCHOOL_ADMIN = "ROLE_" + SCHOOL_ADMIN;

    private Roles() {
    }
}
