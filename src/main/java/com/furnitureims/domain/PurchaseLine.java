package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.math.BigDecimal;

public record PurchaseLine(
        long id,
        long purchaseBillId,
        long itemModelId,
        int quantity,
        Money rate,
        Money discountAmount,
        Money taxableValue,
        BigDecimal gstRate,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money lineTotal
) {
}
