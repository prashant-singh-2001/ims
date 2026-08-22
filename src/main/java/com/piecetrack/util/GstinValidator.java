package com.piecetrack.util;

import java.util.regex.Pattern;

/**
 * Validates a GSTIN's format and, where an expected state is known, that its 2-digit
 * prefix matches (FR-PUR-02). Shared by the shop profile step of the setup wizard now,
 * and by supplier/customer GSTIN entry from milestone M3 onward.
 */
public final class GstinValidator {

    private static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");

    private GstinValidator() {
    }

    public static boolean isValidFormat(String gstin) {
        return gstin != null && GSTIN_PATTERN.matcher(gstin.trim().toUpperCase()).matches();
    }

    public static boolean stateCodeMatches(String gstin, String expectedStateCode) {
        return gstin != null && expectedStateCode != null
                && gstin.length() >= 2 && gstin.substring(0, 2).equals(expectedStateCode);
    }
}
