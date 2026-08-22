package com.piecetrack.ui.reports;

import com.piecetrack.service.ReportService;

/** Read-only row wrapper for the supplier half of the dues aging report
 *  (docs/03-screens.md 8.3). */
public class SupplierDueRow {

    private final ReportService.SupplierDueRow row;

    public SupplierDueRow(ReportService.SupplierDueRow row) {
        this.row = row;
    }

    public String getSupplierName() {
        return row.supplierName();
    }

    public String getSupplierPhone() {
        return row.supplierPhone() == null ? "" : row.supplierPhone();
    }

    public String getSupplierBillNo() {
        return row.bill().supplierBillNo();
    }

    public String getBillDate() {
        return row.bill().billDate().toString();
    }

    public String getGrandTotal() {
        return row.bill().grandTotal().toDisplayString();
    }

    public String getBalance() {
        return row.balance().toDisplayString();
    }

    public long getDaysOutstanding() {
        return row.daysOutstanding();
    }

    public String getBucket() {
        return ReportRowFormatting.bucketLabel(row.bucket());
    }
}
