package com.furnitureims.repository;

import com.furnitureims.money.Money;

import java.time.LocalDate;

/** One netted (return-excluded) sales line for FR-RPT-03's period report - flat and
 *  already joined to model/category, so {@code ReportService} only needs to group and sum
 *  in Java, the same style {@code DocumentService} already uses for invoice PDFs. */
public record SalesProfitLineRow(
        long invoiceId,
        LocalDate invoiceDate,
        long itemModelId,
        String modelName,
        long categoryId,
        String categoryName,
        Money taxableValue,
        Money cgstAmount,
        Money sgstAmount,
        Money igstAmount,
        Money costAtSale
) {
}
