package io.alensi.insure.platform.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** {@code {"amount": "12500.00", "currency": "KES"}}: amounts travel as strings (api-spec.md). */
public record Money(String amount, String currency) {

    public static Money of(BigDecimal amount, String currency) {
        return amount == null ? null : new Money(amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString(), currency);
    }

    /**
     * The amount, checked against {@code expectedCurrency}; problems are added to {@code violations}
     * under {@code field} and {@code null} is returned.
     */
    public BigDecimal amountIn(String expectedCurrency, String field, Violations violations) {
        if (currency == null || !currency.equals(expectedCurrency)) {
            violations.add(field + ".currency", "must be " + expectedCurrency);
            return null;
        }
        return Decimals.parse(amount, field + ".amount", 2, violations);
    }
}
