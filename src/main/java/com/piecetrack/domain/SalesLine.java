package com.piecetrack.domain;

import com.piecetrack.money.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One sold piece on an invoice - always exactly one piece per line (FR-SAL-01), unlike
 * {@link PurchaseLine}. {@code descriptionSnapshot}/{@code hsnSnapshot}/{@code gstRate}
 * are frozen from the item model at the moment of sale, and {@code costAtSale} is frozen
 * from the piece's landed cost the same way, so both the invoice and its profit stay
 * historically accurate regardless of any later catalogue or cost change (FR-SAL-09).
 * <p>
 * {@code deliveredAt} is {@code null} until the physical piece has reached the customer
 * (FR-SAL-13, M13) - a fulfilment fact tracked independently of {@link Piece.State}, which
 * stays {@code SOLD} throughout.
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
        Money costAtSale,
        LocalDateTime deliveredAt
) {
}
