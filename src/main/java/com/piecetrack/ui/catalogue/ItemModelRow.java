package com.piecetrack.ui.catalogue;

import com.piecetrack.repository.ItemModelSummary;

/** Read-only row wrapper for the item models list (docs/03-screens.md 4.1). Plain getters
 *  are enough here - unlike Categories &amp; Locations, this table has no inline editing,
 *  only row actions. */
public class ItemModelRow {

    private final ItemModelSummary summary;

    public ItemModelRow(ItemModelSummary summary) {
        this.summary = summary;
    }

    public ItemModelSummary getSummary() {
        return summary;
    }

    public long getId() {
        return summary.model().id();
    }

    public String getModelCode() {
        return summary.model().modelCode();
    }

    public String getModelName() {
        return summary.model().modelName();
    }

    public String getCategoryName() {
        return summary.categoryName();
    }

    public String getHsnCode() {
        return summary.model().hsnCode();
    }

    public String getGstRate() {
        return summary.model().gstRate() + "%";
    }

    public String getDefaultPrice() {
        return summary.model().defaultSalePrice() == null ? "-" : summary.model().defaultSalePrice().toDisplayString();
    }

    public long getInStockCount() {
        return summary.inStockCount();
    }

    public String getActiveLabel() {
        return summary.model().active() ? "Active" : "Discontinued";
    }
}
