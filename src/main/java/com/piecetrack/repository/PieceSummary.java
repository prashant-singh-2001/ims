package com.piecetrack.repository;

import com.piecetrack.domain.Piece;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** The piece register row: the piece plus the model/category/location names a list
 *  screen needs, all resolved by the query rather than N+1 lookups. */
public record PieceSummary(Piece piece, String modelName, String modelCode,
                            String categoryName, String locationName) {

    public long daysInStock() {
        return ChronoUnit.DAYS.between(piece.acquiredOn(), LocalDate.now());
    }
}
