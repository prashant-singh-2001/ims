package com.furnitureims.repository;

import com.furnitureims.domain.SalesInvoice;

public record SalesInvoiceListRow(SalesInvoice invoice, String customerName, String customerPhone) {
}
