package com.furnitureims.ui.reports;

import com.furnitureims.service.ReportService;

/** Read-only row wrapper for the customer half of the dues aging report
 *  (docs/03-screens.md 8.3). */
public class CustomerDueRow {

    private final ReportService.CustomerDueRow row;

    public CustomerDueRow(ReportService.CustomerDueRow row) {
        this.row = row;
    }

    public String getCustomerName() {
        return row.customerName();
    }

    public String getCustomerPhone() {
        return row.customerPhone();
    }

    public String getInvoiceNo() {
        return row.invoice().invoiceNo();
    }

    public String getInvoiceDate() {
        return row.invoice().invoiceDate().toString();
    }

    public String getGrandTotal() {
        return row.invoice().grandTotal().toDisplayString();
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
