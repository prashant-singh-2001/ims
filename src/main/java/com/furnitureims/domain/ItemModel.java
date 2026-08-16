package com.furnitureims.domain;

import com.furnitureims.money.Money;

import java.math.BigDecimal;

/**
 * A type of furniture the shop deals in, e.g. "Aspen 3-Seater Sofa". Holds no stock
 * itself - "how many in stock" is always a count over {@link Piece} rows (FR-ITEM-05).
 */
public record ItemModel(
        long id,
        String modelCode,
        String modelName,
        long categoryId,
        String hsnCode,
        BigDecimal gstRate,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        String material,
        String finish,
        String colour,
        Money defaultSalePrice,
        boolean active,
        String notes
) {
}
