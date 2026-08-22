package com.piecetrack.ui.booking;

import com.piecetrack.repository.BookingListRow;

/** Read-only row wrapper for the bookings list (docs/03-screens.md). */
public class BookingRow {

    private final BookingListRow row;

    public BookingRow(BookingListRow row) {
        this.row = row;
    }

    public long getId() {
        return row.invoiceId();
    }

    public String getInvoiceNo() {
        return row.invoiceNo();
    }

    public String getInvoiceDate() {
        return row.invoiceDate().toString();
    }

    public String getCustomerName() {
        return row.customerName();
    }

    public String getItems() {
        return row.deliveredItems() + " of " + row.totalItems();
    }

    public String getStatus() {
        if (row.deliveredItems() == 0) {
            return "Pending";
        }
        if (row.deliveredItems() == row.totalItems()) {
            return "Delivered";
        }
        return "Partly delivered";
    }
}
