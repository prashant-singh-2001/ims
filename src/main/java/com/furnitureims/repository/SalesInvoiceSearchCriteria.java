package com.furnitureims.repository;

import com.furnitureims.domain.SalesInvoice;

import java.time.LocalDate;

public record SalesInvoiceSearchCriteria(
        Long customerId,
        SalesInvoice.Status status,
        LocalDate invoiceDateFrom,
        LocalDate invoiceDateTo
) {
    public static SalesInvoiceSearchCriteria empty() {
        return new SalesInvoiceSearchCriteria(null, null, null, null);
    }
}
