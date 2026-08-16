package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One physical furniture unit - the centre of this system's data model (see
 * docs/02-data-model.md section 1). Six identical chairs are six {@code Piece} rows, each
 * with its own tag and its own cost.
 */
public record Piece(
        long id,
        String tag,
        long itemModelId,
        SourceType sourceType,
        Long purchaseLineId,
        Money landedCost,
        Long locationId,
        State state,
        String stateReason,
        LocalDate acquiredOn,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public enum SourceType { PURCHASE, OPENING_STOCK }

    /** See docs/02-data-model.md section 3 for the full transition diagram. Legal
     *  transitions are enforced by {@code PieceService}, not by this enum itself. */
    public enum State { IN_STOCK, SOLD, RETURNED_TO_SUPPLIER, DAMAGED, WRITTEN_OFF }
}
