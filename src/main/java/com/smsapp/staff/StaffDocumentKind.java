package com.smsapp.staff;

import java.util.Locale;
import java.util.Optional;

/** The document slots on the Add Staff form, in display order. */
public enum StaffDocumentKind {
    RESUME("Resume"),
    JOINING_LETTER("Joining Letter"),
    RESIGNATION_LETTER("Resignation Letter"),
    OTHER("Other Documents");

    private final String title;

    StaffDocumentKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** Accepts the enum name in any case, with hyphens or underscores ("joining-letter"). */
    public static Optional<StaffDocumentKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (StaffDocumentKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
