package com.piecetrack.repository;

import com.piecetrack.domain.SalesInvoice;

public record SalesInvoiceListRow(SalesInvoice invoice, String customerName, String customerPhone) {
}
