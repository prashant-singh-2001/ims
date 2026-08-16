package com.furnitureims.repository;

import java.math.BigDecimal;

/** Backs FR-ITEM-04's search screen. Every field is optional; null/blank means "no filter". */
public record ItemModelSearchCriteria(
        String searchText,
        Long categoryId,
        boolean activeOnly,
        BigDecimal minLengthCm, BigDecimal maxLengthCm,
        BigDecimal minWidthCm, BigDecimal maxWidthCm,
        BigDecimal minHeightCm, BigDecimal maxHeightCm
) {
    public static ItemModelSearchCriteria defaultCriteria() {
        return new ItemModelSearchCriteria(null, null, true, null, null, null, null, null, null);
    }
}
