package com.piecetrack.ui.sales;

import com.piecetrack.domain.SalesLine;

/** Read-only row wrapper for the invoice detail line-items table. */
public class SalesLineRow {

    private final SalesLine line;
    private final String tag;

    public SalesLineRow(SalesLine line, String tag) {
        this.line = line;
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

    public String getDescription() {
        return line.descriptionSnapshot();
    }

    public String getUnitPrice() {
        return line.unitPrice().toDisplayString();
    }

    public String getDiscountAmount() {
        return line.discountAmount().toDisplayString();
    }

    public String getTaxableValue() {
        return line.taxableValue().toDisplayString();
    }

    public String getTax() {
        return line.cgstAmount().plus(line.sgstAmount()).plus(line.igstAmount()).toDisplayString();
    }

    public String getLineTotal() {
        return line.lineTotal().toDisplayString();
    }
}
