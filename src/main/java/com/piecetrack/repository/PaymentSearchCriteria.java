package com.piecetrack.repository;

import com.piecetrack.domain.Payment;

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
