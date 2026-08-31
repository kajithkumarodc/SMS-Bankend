package com.smsapp.user;

/**
 * Well-known role identifiers. Until a per-tenant role/permission module exists,
 * these constants keep role names out of scattered string literals in business
 * logic (see plan section 7.3).
 */
public final class Roles {

    public static final String SCHOOL_ADMIN = "SCHOOL_ADMIN";
    public static final String TEACHER = "TEACHER";

    private Roles() {
    }
}
