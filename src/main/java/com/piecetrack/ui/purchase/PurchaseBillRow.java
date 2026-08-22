package com.piecetrack.ui.purchase;

import com.piecetrack.money.Money;
import com.piecetrack.repository.PurchaseBillListRow;

/** Read-only row wrapper for the purchase bills list (docs/03-screens.md 5.3). */
public class PurchaseBillRow {

    private final PurchaseBillListRow row;
    private final Money balance;

    public PurchaseBillRow(PurchaseBillListRow row, Money balance) {
        this.row = row;
        this.balance = balance;
    }

    public PurchaseBillListRow getRow() {
        return row;
    }

    public long getId() {
        return row.bill().id();
    }

    public String getSupplierName() {
        return row.supplierName();
    }

    public String getBillNo() {
        return row.bill().supplierBillNo();
    }

    public String getBillDate() {
        return row.bill().billDate().toString();
    }

    public String getGrandTotal() {
        return row.bill().grandTotal().toDisplayString();
    }

    public String getBalance() {
        return balance.toDisplayString();
    }

    public String getStatus() {
        return row.bill().status().name();
    }
}
