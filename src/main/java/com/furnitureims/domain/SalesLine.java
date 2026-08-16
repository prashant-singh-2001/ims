package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.math.BigDecimal;

/**
 * One sold piece on an invoice - always exactly one piece per line (FR-SAL-01), unlike
 * {@link PurchaseLine}. {@code descriptionSnapshot}/{@code hsnSnapshot}/{@code gstRate}
 * are frozen from the item model at the moment of sale, and {@code costAtSale} is frozen
 * from the piece's landed cost the same way, so both the invoice and its profit stay
 * historically accurate regardless of any later catalogue or cost change (FR-SAL-09).
 */
public record SalesLine(
        long id,
        long salesInvoiceId,
        long pieceId,
        long itemModelId,
        String descriptionSnapshot,
        String hsnSnapshot,
        BigDecimal gstRate,
        Money unitPrice,
        Money discountAmount,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money lineTotal,
        Money costAtSale
) {
}
