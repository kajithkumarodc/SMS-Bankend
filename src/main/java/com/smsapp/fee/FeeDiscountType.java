package com.smsapp.fee;

import java.util.Set;

/** How a {@link FeeDiscount}'s {@code value} is interpreted. The DB CHECK constraint (V26) is the source of truth. */
public final class FeeDiscountType {

    public static final String FIXED = "FIXED";
    public static final String PERCENTAGE = "PERCENTAGE";

    private static final Set<String> ALL = Set.of(FIXED, PERCENTAGE);

    private FeeDiscountType() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }
}
