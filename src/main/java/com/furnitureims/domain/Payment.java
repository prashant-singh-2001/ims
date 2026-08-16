package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single receipt or payment (FR-PAY-02/05). Soft-deletable (FR-PAY-06) - {@code deleted}
 * payments are excluded from every balance calculation but the row, its reason and its
 * timestamp are kept forever.
 */
public record Payment(
        long id,
        Direction direction,
        PartyType partyType,
        long partyId,
        LocalDate paymentDate,
        Money amount,
        Mode mode,
        String referenceNo,
        String note,
        boolean deleted,
        String deletedReason,
        LocalDateTime deletedAt
) {
    public enum Direction { IN, OUT }

    public enum PartyType { CUSTOMER, SUPPLIER }

    public enum Mode { CASH, UPI, CARD, BANK_TRANSFER, CHEQUE }
}
