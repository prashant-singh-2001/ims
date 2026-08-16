package com.furnitureims.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * Every rupee amount in this application flows through this type. Per NFR-13, money is
 * never a {@code double} anywhere - floating point cannot represent ₹0.10 exactly, and a
 * GST invoice that is a few paise off is not a detail, it's a defect.
 * <p>
 * Storage is an {@code INTEGER} paisa column (see docs/02-data-model.md section 4, "Money
 * is stored as INTEGER paisa"); this class is the one converter between that and the
 * {@link BigDecimal} rupee value the rest of the code and the UI work with.
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = Money.ofPaisa(0);

    private static final int PAISA_PER_RUPEE = 100;

    private final long paisa;

    private Money(long paisa) {
        this.paisa = paisa;
    }

    public static Money ofPaisa(long paisa) {
        return new Money(paisa);
    }

    /** Rounds to the nearest paisa using HALF_EVEN, the standard rounding rule for money. */
    public static Money ofRupees(BigDecimal rupees) {
        Objects.requireNonNull(rupees, "rupees");
        BigDecimal paisa = rupees.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN);
        return new Money(paisa.longValueExact());
    }

    public static Money ofRupees(String rupees) {
        return ofRupees(new BigDecimal(rupees));
    }

    public long paisa() {
        return paisa;
    }

    public BigDecimal rupees() {
        return BigDecimal.valueOf(paisa).movePointLeft(2);
    }

    public Money plus(Money other) {
        return new Money(this.paisa + other.paisa);
    }

    public Money minus(Money other) {
        return new Money(this.paisa - other.paisa);
    }

    public Money negate() {
        return new Money(-this.paisa);
    }

    /** Apportions a bill-level amount across line values (FR-PUR-06's landed-cost formula),
     *  putting any leftover paisa from integer rounding onto the last share so the shares
     *  always sum back to exactly this amount - never a paisa more or less. */
    public Money[] apportion(BigDecimal[] weights) {
        BigDecimal totalWeight = java.util.Arrays.stream(weights).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() == 0) {
            Money[] zeros = new Money[weights.length];
            java.util.Arrays.fill(zeros, Money.ZERO);
            return zeros;
        }
        Money[] shares = new Money[weights.length];
        long allocated = 0;
        for (int i = 0; i < weights.length - 1; i++) {
            BigDecimal share = BigDecimal.valueOf(paisa)
                    .multiply(weights[i])
                    .divide(totalWeight, 0, RoundingMode.FLOOR);
            shares[i] = Money.ofPaisa(share.longValueExact());
            allocated += shares[i].paisa;
        }
        shares[weights.length - 1] = Money.ofPaisa(paisa - allocated);
        return shares;
    }

    public boolean isNegative() {
        return paisa < 0;
    }

    public boolean isZero() {
        return paisa == 0;
    }

    public boolean isPositive() {
        return paisa > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.paisa, other.paisa);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && this.paisa == other.paisa;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(paisa);
    }

    /** ₹1,50,000.00 - Indian digit grouping, per NFR-12. */
    public String toDisplayString() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("en", "IN"));
        DecimalFormat format = new DecimalFormat("#,##,##0.00", symbols);
        return "₹" + format.format(rupees());
    }

    @Override
    public String toString() {
        return toDisplayString();
    }
}
