package com.furnitureims.repository;

import com.furnitureims.domain.Payment;

import java.time.LocalDate;

public record PaymentSearchCriteria(
        Payment.PartyType partyType,
        Long partyId,
        Payment.Mode mode,
        LocalDate dateFrom,
        LocalDate dateTo,
        boolean includeDeleted
) {
    public static PaymentSearchCriteria empty() {
        return new PaymentSearchCriteria(null, null, null, null, null, true);
    }
}
