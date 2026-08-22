package com.piecetrack.domain;

import com.piecetrack.money.Money;

import java.time.LocalDate;

/**
 * A supplier's bill for goods received. Confirming receipt (DRAFT -> RECEIVED) is what
 * creates pieces (FR-PUR-05); a DRAFT has no stock effect at all and stays fully editable.
 */
public record PurchaseBill(
        long id,
        long supplierId,
        String supplierBillNo,
        LocalDate billDate,
        LocalDate receivedDate,
        boolean interstate,
        Money taxableValue,
        Money freight,
        Money loadingCharges,
        Money otherCharges,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money roundOff,
        Money grandTotal,
        Status status,
        String notes
) {
    public enum Status { DRAFT, RECEIVED, REVERSED }

    public Money chargePool() {
        return freight.plus(loadingCharges).plus(otherCharges);
    }
}
