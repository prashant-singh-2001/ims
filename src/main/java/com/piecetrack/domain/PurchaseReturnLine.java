package com.piecetrack.domain;

import com.piecetrack.money.Money;

/** piece_id is unique across this table - a piece can be returned to the supplier once. */
public record PurchaseReturnLine(
        long id,
        long purchaseReturnId,
        long pieceId,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money lineTotal
) {
}
