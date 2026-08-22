package com.piecetrack.ui.purchase;

import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;

/** Read-only row wrapper for the suppliers list (docs/03-screens.md 5.1). */
public class SupplierRow {

    private final Supplier supplier;
    private final Money dues;

    public SupplierRow(Supplier supplier, Money dues) {
        this.supplier = supplier;
        this.dues = dues;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public String getName() {
        return supplier.name();
    }

    public String getGstin() {
        return supplier.gstin() == null ? "-" : supplier.gstin();
    }

    public String getStateName() {
        return supplier.stateName() == null ? "-" : supplier.stateName();
    }

    public String getPhone() {
        return supplier.phone() == null ? "-" : supplier.phone();
    }

    public String getDues() {
        return dues.toDisplayString();
    }

    public String getStatusLabel() {
        return supplier.active() ? "Active" : "Inactive";
    }
}
