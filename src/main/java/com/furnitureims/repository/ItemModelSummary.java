package com.furnitureims.repository;

import com.furnitureims.domain.ItemModel;

/** The item models list row (docs/03-screens.md 4.1): the model plus its category name
 *  and its live in-stock count (FR-ITEM-05) - both computed by the query, never stored. */
public record ItemModelSummary(ItemModel model, String categoryName, long inStockCount) {
}
