package com.piecetrack.repository;

/**
 * Backs FR-ITEM-04's search screen. Every field is optional; null/blank means "no filter".
 * M15: the dimension-range fields this record used to carry (minLengthCm/maxLengthCm etc.)
 * were removed rather than ported - no screen ever passed anything but null for them (see
 * ItemModelListController), so they were dead code even before the generic rename made
 * dimensions themselves a custom attribute rather than a fixed column.
 */
public record ItemModelSearchCriteria(
        String searchText,
        Long categoryId,
        boolean activeOnly
) {
    public static ItemModelSearchCriteria defaultCriteria() {
        return new ItemModelSearchCriteria(null, null, true);
    }
}
