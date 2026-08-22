package com.piecetrack.repository;

import java.time.LocalDate;

/**
 * One row of {@code BookingRepository}'s aggregate query (M13, FR-SAL-13) - an invoice with
 * its delivered/total item counts. Booking status itself is never stored, only derived from
 * these two counts (see {@code BookingService}), for the same reason customer balances are
 * computed rather than stored (docs/02-data-model.md section 4).
 */
public record BookingListRow(long invoiceId, String invoiceNo, LocalDate invoiceDate, String customerName,
                              int totalItems, int deliveredItems) {
}
