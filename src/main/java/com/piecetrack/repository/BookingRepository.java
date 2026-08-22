package com.piecetrack.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-SAL-13 (M13): a "booking" is simply an {@code ACTIVE} invoice viewed by how many of its
 * items have been delivered - there is no separate {@code booking} table. {@code
 * si.status = 'ACTIVE'} excludes a cancelled invoice (its pieces are already back {@code
 * IN_STOCK} - {@code SalesInvoiceService.cancelInvoice}), and the {@code NOT EXISTS} against
 * {@code sales_return_line} excludes a piece returned before delivery from both counts - the
 * same pattern {@link ReportRepository} already uses to keep a returned piece out of
 * period-profit figures, applied here so a partial return can never strand a booking at
 * "partly delivered" forever.
 */
@Repository
public class BookingRepository {

    private static final String BASE_QUERY = """
            SELECT si.id AS invoice_id, si.invoice_no, si.invoice_date, c.name AS customer_name,
                   COUNT(sl.id) AS total_items,
                   SUM(CASE WHEN sl.delivered_at IS NOT NULL THEN 1 ELSE 0 END) AS delivered_items
            FROM sales_invoice si
            JOIN customer c ON c.id = si.customer_id
            JOIN sales_line sl ON sl.sales_invoice_id = si.id
            WHERE si.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM sales_return_line srl WHERE srl.sales_line_id = sl.id)
            GROUP BY si.id, si.invoice_no, si.invoice_date, c.name
            """;

    private static final RowMapper<BookingListRow> MAPPER = (rs, rowNum) -> new BookingListRow(
            rs.getLong("invoice_id"),
            rs.getString("invoice_no"),
            LocalDate.parse(rs.getString("invoice_date")),
            rs.getString("customer_name"),
            rs.getInt("total_items"),
            rs.getInt("delivered_items")
    );

    private final JdbcTemplate jdbc;

    public BookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every booking, delivered or not. */
    public List<BookingListRow> findAll() {
        return jdbc.query(BASE_QUERY + "ORDER BY si.invoice_date DESC, si.id DESC", MAPPER);
    }

    /** Bookings with at least one item still undelivered - the list screen's default view. */
    public List<BookingListRow> findPending() {
        return jdbc.query(BASE_QUERY
                + "HAVING SUM(CASE WHEN sl.delivered_at IS NOT NULL THEN 1 ELSE 0 END) < COUNT(sl.id) "
                + "ORDER BY si.invoice_date DESC, si.id DESC", MAPPER);
    }
}
