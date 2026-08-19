package com.furnitureims.ui.booking;

import com.furnitureims.domain.SalesLine;

/** Read-only row wrapper for the booking detail line-items table. */
public class BookingLineRow {

    private final SalesLine line;
    private final String tag;

    public BookingLineRow(SalesLine line, String tag) {
        this.line = line;
        this.tag = tag;
    }

    public long getLineId() {
        return line.id();
    }

    public String getTag() {
        return tag;
    }

    public String getDescription() {
        return line.descriptionSnapshot();
    }

    public boolean isDelivered() {
        return line.deliveredAt() != null;
    }

    public String getDeliveredOn() {
        return line.deliveredAt() == null ? "-" : line.deliveredAt().toString().replace('T', ' ');
    }
}
