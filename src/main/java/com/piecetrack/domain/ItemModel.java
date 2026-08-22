package com.piecetrack.domain;

import com.piecetrack.money.Money;

import java.math.BigDecimal;

/**
 * A type of product the business deals in - the catalogue/SKU entry, e.g. "Aspen 3-Seater
 * Sofa" or "27-inch 4K Monitor". Holds no stock itself - "how many in stock" is always a
 * count over {@link Piece} rows (FR-ITEM-05).
 */
public record ItemModel(
        long id,
        String modelCode,
        String modelName,
        long categoryId,
        String hsnCode,
        BigDecimal gstRate,
        Money defaultSalePrice,
        boolean active,
        String notes
) {
}
