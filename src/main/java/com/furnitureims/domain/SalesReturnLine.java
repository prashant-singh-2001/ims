package com.furnitureims.domain;

import com.furnitureims.money.Money;

/** piece_id is unique across this table - a piece can be returned from a sale once. */
public record SalesReturnLine(
        long id,
        long salesReturnId,
        long pieceId,
        long salesLineId,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money lineTotal
) {
}
