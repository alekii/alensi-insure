package io.alensi.insure.platform.web;

import java.math.BigDecimal;

/** Decimal strings from the API ({@code "4.50"}, {@code "1.15"}), never floating point. */
public final class Decimals {

    private Decimals() {
    }

    /** A non-negative decimal with at most {@code maxScale} decimal places, or null (with a violation). */
    public static BigDecimal parse(String value, String field, int maxScale, Violations violations) {
        if (value == null || value.isBlank()) {
            violations.add(field, "is required");
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(value.trim());
            if (decimal.signum() < 0) {
                violations.add(field, "must not be negative");
                return null;
            }
            if (decimal.stripTrailingZeros().scale() > maxScale) {
                violations.add(field, "must have at most " + maxScale + " decimal places");
                return null;
            }
            return decimal;
        } catch (NumberFormatException ex) {
            violations.add(field, "must be a decimal number as a string, e.g. \"12500.00\"");
            return null;
        }
    }

    public static String format(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
