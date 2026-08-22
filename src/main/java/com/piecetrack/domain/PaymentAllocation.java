package com.piecetrack.domain;

import com.piecetrack.money.Money;

public record PaymentAllocation(long id, long paymentId, TargetType targetType, long targetId, Money amount) {

    public enum TargetType { SALES_INVOICE, PURCHASE_BILL }
}
