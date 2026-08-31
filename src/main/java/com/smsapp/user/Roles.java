package com.smsapp.user;

/**
 * Well-known role identifiers. Until a per-tenant role/permission module exists,
 * these constants keep role names out of scattered string literals in business
 * logic (see plan section 7.3).
 */
public final class Roles {

    public static final String SCHOOL_ADMIN = "SCHOOL_ADMIN";
    public static final String TEACHER = "TEACHER";

    /**
     * Ready-made {@code @PreAuthorize} SpEL expressions, so the authorization rule
     * for a role lives in one place rather than being re-spelled at every endpoint.
     * Compile-time constants, so they are valid in annotation values.
     */
    public static final String HAS_SCHOOL_ADMIN = "hasRole('" + SCHOOL_ADMIN + "')";
    public static final String HAS_SCHOOL_ADMIN_OR_TEACHER =
            "hasAnyRole('" + SCHOOL_ADMIN + "', '" + TEACHER + "')";

    private Roles() {
    }
}
