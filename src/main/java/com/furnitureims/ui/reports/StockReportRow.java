package com.furnitureims.ui.reports;

import com.furnitureims.service.ReportService;

/** Read-only row wrapper for the stock valuation report (docs/03-screens.md 8.1). */
public class StockReportRow {

    private final ReportService.StockValuationRow row;

    public StockReportRow(ReportService.StockValuationRow row) {
        this.row = row;
    }

    public ReportService.StockValuationRow getRow() {
        return row;
    }

    public long getCategoryId() {
        return row.categoryId();
    }

    public String getCategoryName() {
        return row.categoryName();
    }

    public String getModelName() {
        return row.modelName();
    }

    public String getModelCode() {
        return row.modelCode();
    }

    public int getPieceCount() {
        return row.pieceCount();
    }

    public String getTotalValue() {
        return row.totalLandedValue().toDisplayString();
    }

    public long getOldestAgeDays() {
        return row.oldestAgeDays();
    }
}
