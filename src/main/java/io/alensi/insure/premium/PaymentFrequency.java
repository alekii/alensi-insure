package io.alensi.insure.premium;

/** How often premium is collected: one installment every {@link #intervalMonths()}. */
public enum PaymentFrequency {

    ANNUAL(12), SEMI_ANNUAL(6), QUARTERLY(3), MONTHLY(1);

    private final int intervalMonths;

    PaymentFrequency(int intervalMonths) {
        this.intervalMonths = intervalMonths;
    }

    public int intervalMonths() {
        return intervalMonths;
    }

    /** Installments for a term: at least one, and a partial last interval still gets one. */
    public int installmentsFor(int termMonths) {
        return Math.max(1, (termMonths + intervalMonths - 1) / intervalMonths);
    }
}
