package com.smsapp.frontoffice;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The four lookup lists managed on Setup Front Office. Table names and the queries that count where an entry is
 * used are fixed here -- never built from request input -- so the SQL in {@link FrontOfficeSetupService} is safe.
 */
enum FrontOfficeSetupList {

    /** Visitor Book purposes (V32). */
    PURPOSES("purposes", "front_office_purposes", "Purpose",
            List.of("SELECT count(*) FROM visitors WHERE purpose_id = :id")),
    /** Complaint types (V36). */
    COMPLAINT_TYPES("complaint-types", "complaint_types", "Complaint type",
            List.of("SELECT count(*) FROM complaints WHERE complaint_type_id = :id")),
    /** Sources, shared by admission enquiries and complaints (V23). */
    SOURCES("sources", "enquiry_sources", "Source",
            List.of("SELECT count(*) FROM admission_enquiries WHERE source_id = :id",
                    "SELECT count(*) FROM complaints WHERE source_id = :id")),
    /** Admission enquiry references (V31). */
    REFERENCES("references", "enquiry_references", "Reference",
            List.of("SELECT count(*) FROM admission_enquiries WHERE reference_id = :id"));

    final String path;
    final String table;
    final String label;
    final List<String> usageQueries;

    FrontOfficeSetupList(String path, String table, String label, List<String> usageQueries) {
        this.path = path;
        this.table = table;
        this.label = label;
        this.usageQueries = usageQueries;
    }

    static Optional<FrontOfficeSetupList> fromPath(String path) {
        return Arrays.stream(values()).filter(l -> l.path.equals(path)).findFirst();
    }
}
