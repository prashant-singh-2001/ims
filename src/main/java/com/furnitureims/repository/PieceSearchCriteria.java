package com.furnitureims.repository;

import com.furnitureims.domain.Piece;

import java.time.LocalDate;

/** Backs the piece register (docs/03-screens.md 4.3, FR-PIECE-07). Every field is
 *  optional; null means "no filter". */
public record PieceSearchCriteria(
        String tagOrModelSearch,
        Piece.State state,
        Long itemModelId,
        Long categoryId,
        Long locationId,
        LocalDate acquiredFrom,
        LocalDate acquiredTo
) {
    public static PieceSearchCriteria empty() {
        return new PieceSearchCriteria(null, null, null, null, null, null, null);
    }
}
