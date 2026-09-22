package com.smsapp.fee;

import java.util.Locale;
import java.util.Set;

/**
 * Fee-structure line-item categories. Kept as constants (not a persisted JPA
 * enum) so the column stays a plain string like the other status/category
 * columns; the DB CHECK constraint in migration V21 is the source of truth
 * for the allowed set.
 */
public final class FeeStructureItemCategory {

    public static final String APPLICATION = "APPLICATION";
    public static final String ADMISSION = "ADMISSION";
    public static final String TERM_1 = "TERM_1";
    public static final String TERM_2 = "TERM_2";
    public static final String TERM_3 = "TERM_3";
    public static final String TERM_4 = "TERM_4";
    public static final String OTHER = "OTHER";

    private static final Set<String> ALL =
            Set.of(APPLICATION, ADMISSION, TERM_1, TERM_2, TERM_3, TERM_4, OTHER);

    private FeeStructureItemCategory() {
    }

    /** Upper-cases and validates against the allowed set; null/blank/unknown returns null. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(normalized) ? normalized : null;
    }
}
