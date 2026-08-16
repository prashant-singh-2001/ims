package com.furnitureims.repository;

import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class SalesInvoiceRepository {

    private static final RowMapper<SalesInvoice> MAPPER = (rs, rowNum) -> new SalesInvoice(
            rs.getLong("id"),
            rs.getString("invoice_no"),
            rs.getString("financial_year"),
            LocalDate.parse(rs.getString("invoice_date")),
            rs.getLong("customer_id"),
            rs.getString("place_of_supply_state_code"),
            rs.getInt("is_interstate") == 1,
            rs.getInt("is_price_inclusive") == 1,
            Money.ofPaisa(rs.getLong("gross_value")),
            Money.ofPaisa(rs.getLong("line_discount_total")),
            Money.ofPaisa(rs.getLong("bill_discount")),
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("round_off")),
            Money.ofPaisa(rs.getLong("grand_total")),
            SalesInvoice.Status.valueOf(rs.getString("status")),
            rs.getString("cancelled_at") == null ? null : LocalDateTime.parse(rs.getString("cancelled_at")),
            rs.getString("cancel_reason"),
            rs.getString("notes")
    );

    private final JdbcTemplate jdbc;

    public SalesInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SalesInvoice> findById(long id) {
        return jdbc.query("SELECT * FROM sales_invoice WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<SalesInvoice> findByInvoiceNo(String invoiceNo) {
        return jdbc.query("SELECT * FROM sales_invoice WHERE invoice_no = ?", MAPPER, invoiceNo).stream().findFirst();
    }

    /** Called only after {@code SequenceCounterRepository.next} has drawn the number, at
     *  the very last step before the invoice is persisted (FR-SAL-07). */
    public long create(SalesInvoice inv) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sales_invoice (invoice_no, financial_year, invoice_date, customer_id,
                            place_of_supply_state_code, is_interstate, is_price_inclusive, gross_value,
                            line_discount_total, bill_discount, taxable_value, cgst_amount, sgst_amount,
                            igst_amount, round_off, grand_total, status, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, inv.invoiceNo());
            ps.setString(2, inv.financialYear());
            ps.setString(3, inv.invoiceDate().toString());
            ps.setLong(4, inv.customerId());
            ps.setString(5, inv.placeOfSupplyStateCode());
            ps.setInt(6, inv.interstate() ? 1 : 0);
            ps.setInt(7, inv.priceInclusive() ? 1 : 0);
            ps.setLong(8, inv.grossValue().paisa());
            ps.setLong(9, inv.lineDiscountTotal().paisa());
            ps.setLong(10, inv.billDiscount().paisa());
            ps.setLong(11, inv.taxableValue().paisa());
            ps.setLong(12, inv.cgstAmount().paisa());
            ps.setLong(13, inv.sgstAmount().paisa());
            ps.setLong(14, inv.igstAmount().paisa());
            ps.setLong(15, inv.roundOff().paisa());
            ps.setLong(16, inv.grandTotal().paisa());
            ps.setString(17, inv.status().name());
            ps.setString(18, inv.notes());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void cancel(long id, LocalDateTime cancelledAt, String reason) {
        jdbc.update("""
                UPDATE sales_invoice SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = ?,
                        updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')
                WHERE id = ?
                """, cancelledAt.toString(), reason, id);
    }

    public List<SalesInvoiceListRow> search(SalesInvoiceSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT si.*, c.name AS customer_name, c.phone AS customer_phone
                FROM sales_invoice si
                JOIN customer c ON c.id = si.customer_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (criteria.customerId() != null) {
            sql.append(" AND si.customer_id = ?");
            params.add(criteria.customerId());
        }
        if (criteria.status() != null) {
            sql.append(" AND si.status = ?");
            params.add(criteria.status().name());
        }
        if (criteria.invoiceDateFrom() != null) {
            sql.append(" AND si.invoice_date >= ?");
            params.add(criteria.invoiceDateFrom().toString());
        }
        if (criteria.invoiceDateTo() != null) {
            sql.append(" AND si.invoice_date <= ?");
            params.add(criteria.invoiceDateTo().toString());
        }
        sql.append(" ORDER BY si.invoice_date DESC, si.id DESC");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new SalesInvoiceListRow(
                MAPPER.mapRow(rs, rowNum), rs.getString("customer_name"), rs.getString("customer_phone")
        ), params.toArray());
    }
}
