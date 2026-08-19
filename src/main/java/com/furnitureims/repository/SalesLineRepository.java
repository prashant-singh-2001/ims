package com.furnitureims.repository;

import com.furnitureims.domain.SalesLine;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class SalesLineRepository {

    private static final RowMapper<SalesLine> MAPPER = (rs, rowNum) -> new SalesLine(
            rs.getLong("id"),
            rs.getLong("sales_invoice_id"),
            rs.getLong("piece_id"),
            rs.getLong("item_model_id"),
            rs.getString("description_snapshot"),
            rs.getString("hsn_snapshot"),
            rs.getBigDecimal("gst_rate"),
            Money.ofPaisa(rs.getLong("unit_price")),
            Money.ofPaisa(rs.getLong("discount_amount")),
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("line_total")),
            Money.ofPaisa(rs.getLong("cost_at_sale")),
            rs.getString("delivered_at") == null ? null : LocalDateTime.parse(rs.getString("delivered_at"))
    );

    private final JdbcTemplate jdbc;

    public SalesLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SalesLine> findBySalesInvoiceId(long salesInvoiceId) {
        return jdbc.query("SELECT * FROM sales_line WHERE sales_invoice_id = ? ORDER BY id",
                MAPPER, salesInvoiceId);
    }

    public Optional<SalesLine> findByPieceId(long pieceId) {
        return jdbc.query("SELECT * FROM sales_line WHERE piece_id = ?", MAPPER, pieceId).stream().findFirst();
    }

    public long create(SalesLine l) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sales_line (sales_invoice_id, piece_id, item_model_id, description_snapshot,
                            hsn_snapshot, gst_rate, unit_price, discount_amount, taxable_value, cgst_amount,
                            sgst_amount, igst_amount, line_total, cost_at_sale)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, l.salesInvoiceId());
            ps.setLong(2, l.pieceId());
            ps.setLong(3, l.itemModelId());
            ps.setString(4, l.descriptionSnapshot());
            ps.setString(5, l.hsnSnapshot());
            ps.setBigDecimal(6, l.gstRate());
            ps.setLong(7, l.unitPrice().paisa());
            ps.setLong(8, l.discountAmount().paisa());
            ps.setLong(9, l.taxableValue().paisa());
            ps.setLong(10, l.cgstAmount().paisa());
            ps.setLong(11, l.sgstAmount().paisa());
            ps.setLong(12, l.igstAmount().paisa());
            ps.setLong(13, l.lineTotal().paisa());
            ps.setLong(14, l.costAtSale().paisa());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** FR-SAL-13 (M13): {@code deliveredAt} of {@code null} marks the piece as not yet
     *  delivered - clearing a mis-tick is a correction, not an invoice edit. */
    public void updateDeliveredAt(long salesLineId, LocalDateTime deliveredAt) {
        jdbc.update("UPDATE sales_line SET delivered_at = ? WHERE id = ?",
                deliveredAt == null ? null : deliveredAt.toString(), salesLineId);
    }
}
