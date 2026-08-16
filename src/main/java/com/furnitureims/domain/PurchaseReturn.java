package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.time.LocalDate;

public record PurchaseReturn(
        long id,
        long purchaseBillId,
        String debitNoteNo,
        LocalDate returnDate,
        String reason,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money totalAmount,
        String pdfPath
) {
}
