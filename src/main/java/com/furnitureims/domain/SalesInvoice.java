package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A GST sales invoice. There is deliberately no "edited" state (FR-SAL-12) - a saved
 * invoice is corrected by a credit note ({@link SalesReturn}) or by cancellation, never
 * rewritten in place.
 */
public record SalesInvoice(
        long id,
        String invoiceNo,
        String financialYear,
        LocalDate invoiceDate,
        long customerId,
        String placeOfSupplyStateCode,
        boolean interstate,
        boolean priceInclusive,
        Money grossValue,
        Money lineDiscountTotal,
        Money billDiscount,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money roundOff,
        Money grandTotal,
        Status status,
        LocalDateTime cancelledAt,
        String cancelReason,
        String notes
) {
    public enum Status { ACTIVE, CANCELLED }
}
