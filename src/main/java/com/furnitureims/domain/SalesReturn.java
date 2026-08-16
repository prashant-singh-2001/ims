package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.time.LocalDate;

public record SalesReturn(
        long id,
        long salesInvoiceId,
        String creditNoteNo,
        LocalDate returnDate,
        String reason,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money totalAmount,
        RefundMode refundMode,
        String pdfPath
) {
    public enum RefundMode { ADJUST_AGAINST_DUE, CASH_REFUND }
}
