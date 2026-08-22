package com.piecetrack.ui.reports;

import com.piecetrack.service.ReportService;

/** Read-only row wrapper for the sales and profit report's breakdown tables
 *  (docs/03-screens.md 8.2) - one shape reused for the by-day/month/category/model tabs. */
public class SalesProfitRow {

    private final ReportService.SalesProfitAggregate aggregate;

    public SalesProfitRow(ReportService.SalesProfitAggregate aggregate) {
        this.aggregate = aggregate;
    }

    public String getLabel() {
        return aggregate.label();
    }

    public long getInvoiceCount() {
        return aggregate.invoiceCount();
    }

    public String getTaxableValue() {
        return aggregate.taxableValue().toDisplayString();
    }

    public String getTax() {
        return aggregate.tax().toDisplayString();
    }

    public String getTotalSales() {
        return aggregate.totalSales().toDisplayString();
    }

    public String getCost() {
        return aggregate.cost().toDisplayString();
    }

    public String getProfit() {
        return aggregate.profit().toDisplayString();
    }

    public String getMarginPercent() {
        return aggregate.marginPercent().toPlainString() + "%";
    }
}
