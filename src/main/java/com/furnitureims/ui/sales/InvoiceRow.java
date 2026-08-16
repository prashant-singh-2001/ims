package com.furnitureims.ui.sales;

import com.furnitureims.money.Money;
import com.furnitureims.repository.SalesInvoiceListRow;

/** Read-only row wrapper for the invoices list (docs/03-screens.md 6.2). */
public class InvoiceRow {

    private final SalesInvoiceListRow row;
    private final Money balance;

    public InvoiceRow(SalesInvoiceListRow row, Money balance) {
        this.row = row;
        this.balance = balance;
    }

    public SalesInvoiceListRow getRow() {
        return row;
    }

    public long getId() {
        return row.invoice().id();
    }

    public String getInvoiceNo() {
        return row.invoice().invoiceNo();
    }

    public String getInvoiceDate() {
        return row.invoice().invoiceDate().toString();
    }

    public String getCustomerName() {
        return row.customerName();
    }

    public String getGrandTotal() {
        return row.invoice().grandTotal().toDisplayString();
    }

    public String getBalance() {
        return balance.toDisplayString();
    }

    public String getStatus() {
        return row.invoice().status().name();
    }
}
