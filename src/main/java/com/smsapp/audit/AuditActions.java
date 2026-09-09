package com.smsapp.audit;

/**
 * Well-known audit action + entity-type identifiers. Keeps these strings out of
 * scattered literals in the services (plan section 7.3).
 */
public final class AuditActions {

    // actions
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String STUDENT_CREATED = "STUDENT_CREATED";
    public static final String STUDENT_UPDATED = "STUDENT_UPDATED";
    public static final String STUDENT_STATUS_CHANGED = "STUDENT_STATUS_CHANGED";
    public static final String SECTION_ASSIGNED = "SECTION_ASSIGNED";
    public static final String CLASS_CREATED = "CLASS_CREATED";
    public static final String SECTION_CREATED = "SECTION_CREATED";
    public static final String SUBJECT_CREATED = "SUBJECT_CREATED";
    public static final String CLASS_SUBJECT_ASSIGNED = "CLASS_SUBJECT_ASSIGNED";
    public static final String ATTENDANCE_MARKED = "ATTENDANCE_MARKED";
    public static final String ATTENDANCE_CHANGED = "ATTENDANCE_CHANGED";
    public static final String EXAM_CREATED = "EXAM_CREATED";
    public static final String EXAM_MARK_RECORDED = "EXAM_MARK_RECORDED";
    public static final String EXAM_MARK_CHANGED = "EXAM_MARK_CHANGED";
    public static final String FEE_STRUCTURE_CREATED = "FEE_STRUCTURE_CREATED";
    public static final String INVOICE_CREATED = "INVOICE_CREATED";
    public static final String INVOICE_CHECKOUT_STARTED = "INVOICE_CHECKOUT_STARTED";
    public static final String INVOICE_PAID = "INVOICE_PAID";
    /** DEV-ONLY: an invoice marked paid through the dev-tools simulate endpoint, not a real webhook. */
    public static final String INVOICE_PAID_SIMULATED = "INVOICE_PAID_SIMULATED";
    public static final String ANNOUNCEMENT_CREATED = "ANNOUNCEMENT_CREATED";
    public static final String ANNOUNCEMENT_DELETED = "ANNOUNCEMENT_DELETED";
    public static final String LIBRARY_BOOK_ADDED = "LIBRARY_BOOK_ADDED";
    public static final String LIBRARY_BOOK_ISSUED = "LIBRARY_BOOK_ISSUED";
    public static final String LIBRARY_BOOK_RETURNED = "LIBRARY_BOOK_RETURNED";

    // entity types
    public static final String USER = "USER";
    public static final String STUDENT = "STUDENT";
    public static final String CLASS = "CLASS";
    public static final String SECTION = "SECTION";
    public static final String SUBJECT = "SUBJECT";
    public static final String CLASS_SUBJECT = "CLASS_SUBJECT";
    public static final String ATTENDANCE_RECORD = "ATTENDANCE_RECORD";
    public static final String EXAM = "EXAM";
    public static final String EXAM_MARK = "EXAM_MARK";
    public static final String FEE_STRUCTURE = "FEE_STRUCTURE";
    public static final String INVOICE = "INVOICE";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String LIBRARY_BOOK = "LIBRARY_BOOK";
    public static final String BOOK_LOAN = "BOOK_LOAN";

    private AuditActions() {
    }
}
