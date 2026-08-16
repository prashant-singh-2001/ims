package com.furnitureims.repository;

import com.furnitureims.domain.PurchaseBill;

import java.time.LocalDate;

public record PurchaseBillSearchCriteria(
        Long supplierId,
        PurchaseBill.Status status,
        LocalDate billDateFrom,
        LocalDate billDateTo
) {
    public static PurchaseBillSearchCriteria empty() {
        return new PurchaseBillSearchCriteria(null, null, null, null);
    }
}
