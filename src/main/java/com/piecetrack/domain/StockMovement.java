package com.piecetrack.domain;

import java.time.LocalDateTime;

/**
 * One append-only entry in a piece's history. Written in the same transaction as every
 * state or location change to {@link Piece} - the two must never disagree (see
 * docs/02-data-model.md section 3).
 */
public record StockMovement(
        long id,
        long pieceId,
        Type movementType,
        Piece.State fromState,
        Piece.State toState,
        Long fromLocationId,
        Long toLocationId,
        String refType,
        Long refId,
        LocalDateTime movedAt,
        String note
) {
    public enum Type {
        RECEIPT, OPENING, SALE, SALES_RETURN, INVOICE_CANCELLED,
        PURCHASE_RETURN, DAMAGE, REPAIR, WRITE_OFF, LOCATION_CHANGE, TAG_RENAMED
    }
}
