package com.smsapp.user;

/**
 * Ready-made {@code @PreAuthorize("hasAuthority(...)")} SpEL expressions for the
 * RBAC Phase 1 permission system (V22 migration), same convention as {@link Roles}'
 * {@code HAS_*} role expressions. Unlike roles, permission NAMES themselves are
 * database rows (seeded by migration, editable from the Roles & Permissions admin
 * screen) -- these constants only fix the string literal each endpoint checks for,
 * so a typo is a compile error instead of a silent always-false check.
 *
 * <p>Front Office (Phase 2) is the first module built against permissions instead
 * of fixed roles -- see {@code com.smsapp.frontoffice.EnquiryController}.
 */
public final class Permissions {

    private static final String HAS_AUTHORITY_OPEN = "hasAuthority('";
    private static final String CLOSE = "')";

    public static final String STUDENT_VIEW = "STUDENT_VIEW";
    public static final String STUDENT_CREATE = "STUDENT_CREATE";
    public static final String STUDENT_EDIT = "STUDENT_EDIT";
    public static final String STUDENT_DELETE = "STUDENT_DELETE";
    public static final String STUDENT_EXPORT = "STUDENT_EXPORT";
    public static final String STUDENT_PRINT = "STUDENT_PRINT";
    public static final String STUDENT_DOCUMENT_VIEW = "STUDENT_DOCUMENT_VIEW";
    public static final String STUDENT_DOCUMENT_UPLOAD = "STUDENT_DOCUMENT_UPLOAD";
    public static final String STUDENT_DOCUMENT_DELETE = "STUDENT_DOCUMENT_DELETE";

    public static final String STUDENT_PROMOTE = "STUDENT_PROMOTE";

    public static final String FEE_VIEW = "FEE_VIEW";
    public static final String FEE_CREATE = "FEE_CREATE";
    public static final String FEE_EDIT = "FEE_EDIT";
    public static final String FEE_DELETE = "FEE_DELETE";
    public static final String FEE_APPROVE = "FEE_APPROVE";
    public static final String FEE_EXPORT = "FEE_EXPORT";
    public static final String FEE_PRINT = "FEE_PRINT";
    public static final String FEE_ASSIGN = "FEE_ASSIGN";
    public static final String FEE_COLLECT = "FEE_COLLECT";
    public static final String FEE_DISCOUNT = "FEE_DISCOUNT";
    public static final String FEE_REFUND = "FEE_REFUND";
    public static final String FEE_RECEIPT_PRINT = "FEE_RECEIPT_PRINT";

    public static final String ADMISSION_APPLICATION_VIEW = "ADMISSION_APPLICATION_VIEW";
    public static final String ADMISSION_APPLICATION_CREATE = "ADMISSION_APPLICATION_CREATE";
    public static final String ADMISSION_APPLICATION_EDIT = "ADMISSION_APPLICATION_EDIT";
    public static final String ADMISSION_APPLICATION_REVIEW = "ADMISSION_APPLICATION_REVIEW";
    public static final String ADMISSION_APPLICATION_APPROVE = "ADMISSION_APPLICATION_APPROVE";
    public static final String ADMISSION_APPLICATION_REJECT = "ADMISSION_APPLICATION_REJECT";
    public static final String ADMISSION_APPLICATION_WAITLIST = "ADMISSION_APPLICATION_WAITLIST";
    public static final String ADMISSION_APPLICATION_DOCUMENT_VIEW = "ADMISSION_APPLICATION_DOCUMENT_VIEW";
    public static final String ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD = "ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD";
    public static final String ADMISSION_APPLICATION_EXPORT = "ADMISSION_APPLICATION_EXPORT";
    public static final String ADMISSION_CYCLE_VIEW = "ADMISSION_CYCLE_VIEW";
    public static final String ADMISSION_CYCLE_CREATE = "ADMISSION_CYCLE_CREATE";
    public static final String ADMISSION_CYCLE_EDIT = "ADMISSION_CYCLE_EDIT";
    public static final String ADMISSION_CYCLE_OPEN = "ADMISSION_CYCLE_OPEN";
    public static final String ADMISSION_CYCLE_CLOSE = "ADMISSION_CYCLE_CLOSE";

    public static final String ENQUIRY_VIEW = "ENQUIRY_VIEW";
    public static final String ENQUIRY_CREATE = "ENQUIRY_CREATE";
    public static final String ENQUIRY_EDIT = "ENQUIRY_EDIT";
    public static final String ENQUIRY_DELETE = "ENQUIRY_DELETE";
    public static final String ENQUIRY_FOLLOWUP = "ENQUIRY_FOLLOWUP";
    public static final String ENQUIRY_CONVERT = "ENQUIRY_CONVERT";
    public static final String ENQUIRY_EXPORT = "ENQUIRY_EXPORT";
    public static final String ENQUIRY_PRINT = "ENQUIRY_PRINT";

    public static final String VISITOR_VIEW = "VISITOR_VIEW";
    public static final String VISITOR_CREATE = "VISITOR_CREATE";
    public static final String VISITOR_EDIT = "VISITOR_EDIT";
    public static final String VISITOR_DELETE = "VISITOR_DELETE";
    public static final String VISITOR_EXPORT = "VISITOR_EXPORT";
    public static final String VISITOR_PRINT = "VISITOR_PRINT";

    public static final String PHONE_CALL_VIEW = "PHONE_CALL_VIEW";
    public static final String PHONE_CALL_CREATE = "PHONE_CALL_CREATE";
    public static final String PHONE_CALL_EDIT = "PHONE_CALL_EDIT";
    public static final String PHONE_CALL_DELETE = "PHONE_CALL_DELETE";
    public static final String PHONE_CALL_EXPORT = "PHONE_CALL_EXPORT";
    public static final String PHONE_CALL_PRINT = "PHONE_CALL_PRINT";

    public static final String POSTAL_DISPATCH_VIEW = "POSTAL_DISPATCH_VIEW";
    public static final String POSTAL_DISPATCH_CREATE = "POSTAL_DISPATCH_CREATE";
    public static final String POSTAL_DISPATCH_EDIT = "POSTAL_DISPATCH_EDIT";
    public static final String POSTAL_DISPATCH_DELETE = "POSTAL_DISPATCH_DELETE";
    public static final String POSTAL_DISPATCH_EXPORT = "POSTAL_DISPATCH_EXPORT";
    public static final String POSTAL_DISPATCH_PRINT = "POSTAL_DISPATCH_PRINT";

    public static final String HAS_STUDENT_VIEW = HAS_AUTHORITY_OPEN + STUDENT_VIEW + CLOSE;
    public static final String HAS_STUDENT_CREATE = HAS_AUTHORITY_OPEN + STUDENT_CREATE + CLOSE;
    public static final String HAS_STUDENT_EDIT = HAS_AUTHORITY_OPEN + STUDENT_EDIT + CLOSE;
    public static final String HAS_STUDENT_DELETE = HAS_AUTHORITY_OPEN + STUDENT_DELETE + CLOSE;
    public static final String HAS_STUDENT_DOCUMENT_VIEW = HAS_AUTHORITY_OPEN + STUDENT_DOCUMENT_VIEW + CLOSE;
    public static final String HAS_STUDENT_DOCUMENT_UPLOAD = HAS_AUTHORITY_OPEN + STUDENT_DOCUMENT_UPLOAD + CLOSE;
    public static final String HAS_STUDENT_DOCUMENT_DELETE = HAS_AUTHORITY_OPEN + STUDENT_DOCUMENT_DELETE + CLOSE;
    public static final String HAS_STUDENT_PROMOTE = HAS_AUTHORITY_OPEN + STUDENT_PROMOTE + CLOSE;

    public static final String HAS_FEE_VIEW = HAS_AUTHORITY_OPEN + FEE_VIEW + CLOSE;
    public static final String HAS_FEE_CREATE = HAS_AUTHORITY_OPEN + FEE_CREATE + CLOSE;
    public static final String HAS_FEE_EDIT = HAS_AUTHORITY_OPEN + FEE_EDIT + CLOSE;
    public static final String HAS_FEE_DELETE = HAS_AUTHORITY_OPEN + FEE_DELETE + CLOSE;
    public static final String HAS_FEE_APPROVE = HAS_AUTHORITY_OPEN + FEE_APPROVE + CLOSE;
    public static final String HAS_FEE_EXPORT = HAS_AUTHORITY_OPEN + FEE_EXPORT + CLOSE;
    public static final String HAS_FEE_PRINT = HAS_AUTHORITY_OPEN + FEE_PRINT + CLOSE;
    public static final String HAS_FEE_ASSIGN = HAS_AUTHORITY_OPEN + FEE_ASSIGN + CLOSE;
    public static final String HAS_FEE_COLLECT = HAS_AUTHORITY_OPEN + FEE_COLLECT + CLOSE;
    public static final String HAS_FEE_DISCOUNT = HAS_AUTHORITY_OPEN + FEE_DISCOUNT + CLOSE;
    public static final String HAS_FEE_REFUND = HAS_AUTHORITY_OPEN + FEE_REFUND + CLOSE;
    public static final String HAS_FEE_RECEIPT_PRINT = HAS_AUTHORITY_OPEN + FEE_RECEIPT_PRINT + CLOSE;

    public static final String HAS_ADMISSION_APPLICATION_VIEW = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_VIEW + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_CREATE = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_CREATE + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_EDIT = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_EDIT + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_REVIEW = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_REVIEW + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_APPROVE = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_APPROVE + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_REJECT = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_REJECT + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_WAITLIST = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_WAITLIST + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_DOCUMENT_VIEW =
            HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_DOCUMENT_VIEW + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD =
            HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD + CLOSE;
    public static final String HAS_ADMISSION_APPLICATION_EXPORT = HAS_AUTHORITY_OPEN + ADMISSION_APPLICATION_EXPORT + CLOSE;
    public static final String HAS_ADMISSION_CYCLE_VIEW = HAS_AUTHORITY_OPEN + ADMISSION_CYCLE_VIEW + CLOSE;
    public static final String HAS_ADMISSION_CYCLE_CREATE = HAS_AUTHORITY_OPEN + ADMISSION_CYCLE_CREATE + CLOSE;
    public static final String HAS_ADMISSION_CYCLE_EDIT = HAS_AUTHORITY_OPEN + ADMISSION_CYCLE_EDIT + CLOSE;
    public static final String HAS_ADMISSION_CYCLE_OPEN = HAS_AUTHORITY_OPEN + ADMISSION_CYCLE_OPEN + CLOSE;
    public static final String HAS_ADMISSION_CYCLE_CLOSE = HAS_AUTHORITY_OPEN + ADMISSION_CYCLE_CLOSE + CLOSE;

    public static final String HAS_ENQUIRY_VIEW = HAS_AUTHORITY_OPEN + ENQUIRY_VIEW + CLOSE;
    public static final String HAS_ENQUIRY_CREATE = HAS_AUTHORITY_OPEN + ENQUIRY_CREATE + CLOSE;
    public static final String HAS_ENQUIRY_EDIT = HAS_AUTHORITY_OPEN + ENQUIRY_EDIT + CLOSE;
    public static final String HAS_ENQUIRY_DELETE = HAS_AUTHORITY_OPEN + ENQUIRY_DELETE + CLOSE;
    public static final String HAS_ENQUIRY_FOLLOWUP = HAS_AUTHORITY_OPEN + ENQUIRY_FOLLOWUP + CLOSE;
    public static final String HAS_ENQUIRY_CONVERT = HAS_AUTHORITY_OPEN + ENQUIRY_CONVERT + CLOSE;
    public static final String HAS_ENQUIRY_EXPORT = HAS_AUTHORITY_OPEN + ENQUIRY_EXPORT + CLOSE;
    public static final String HAS_ENQUIRY_PRINT = HAS_AUTHORITY_OPEN + ENQUIRY_PRINT + CLOSE;

    public static final String HAS_VISITOR_VIEW = HAS_AUTHORITY_OPEN + VISITOR_VIEW + CLOSE;
    public static final String HAS_VISITOR_CREATE = HAS_AUTHORITY_OPEN + VISITOR_CREATE + CLOSE;
    public static final String HAS_VISITOR_EDIT = HAS_AUTHORITY_OPEN + VISITOR_EDIT + CLOSE;
    public static final String HAS_VISITOR_DELETE = HAS_AUTHORITY_OPEN + VISITOR_DELETE + CLOSE;

    public static final String HAS_PHONE_CALL_VIEW = HAS_AUTHORITY_OPEN + PHONE_CALL_VIEW + CLOSE;
    public static final String HAS_PHONE_CALL_CREATE = HAS_AUTHORITY_OPEN + PHONE_CALL_CREATE + CLOSE;
    public static final String HAS_PHONE_CALL_EDIT = HAS_AUTHORITY_OPEN + PHONE_CALL_EDIT + CLOSE;
    public static final String HAS_PHONE_CALL_DELETE = HAS_AUTHORITY_OPEN + PHONE_CALL_DELETE + CLOSE;

    public static final String HAS_POSTAL_DISPATCH_VIEW = HAS_AUTHORITY_OPEN + POSTAL_DISPATCH_VIEW + CLOSE;
    public static final String HAS_POSTAL_DISPATCH_CREATE = HAS_AUTHORITY_OPEN + POSTAL_DISPATCH_CREATE + CLOSE;
    public static final String HAS_POSTAL_DISPATCH_EDIT = HAS_AUTHORITY_OPEN + POSTAL_DISPATCH_EDIT + CLOSE;
    public static final String HAS_POSTAL_DISPATCH_DELETE = HAS_AUTHORITY_OPEN + POSTAL_DISPATCH_DELETE + CLOSE;

    private Permissions() {
    }
}
