package com.piecetrack.ui.catalogue;

import com.piecetrack.repository.PieceSummary;

/** Read-only row wrapper for the piece register (docs/03-screens.md 4.3). */
public class PieceRow {

    private final PieceSummary summary;

    public PieceRow(PieceSummary summary) {
        this.summary = summary;
    }

    public PieceSummary getSummary() {
        return summary;
    }

    public long getId() {
        return summary.piece().id();
    }

    public String getTag() {
        return summary.piece().tag();
    }

    public String getModelName() {
        return summary.modelName() + " (" + summary.modelCode() + ")";
    }

    public String getCategoryName() {
        return summary.categoryName();
    }

    public String getState() {
        return summary.piece().state().name();
    }

    public String getLocationName() {
        return summary.locationName() == null ? "-" : summary.locationName();
    }

    public String getLandedCost() {
        return summary.piece().landedCost().toDisplayString();
    }

    public long getDaysInStock() {
        return summary.daysInStock();
    }

    public String getSource() {
        return summary.piece().sourceType().name();
    }
}
