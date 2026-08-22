package com.piecetrack.repository;

import com.piecetrack.domain.PurchaseLine;
import com.piecetrack.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PurchaseLineRepository {

    private static final RowMapper<PurchaseLine> MAPPER = (rs, rowNum) -> new PurchaseLine(
            rs.getLong("id"),
            rs.getLong("purchase_bill_id"),
            rs.getLong("item_model_id"),
            rs.getInt("quantity"),
            Money.ofPaisa(rs.getLong("rate")),
            Money.ofPaisa(rs.getLong("discount_amount")),
            Money.ofPaisa(rs.getLong("taxable_value")),
            rs.getBigDecimal("gst_rate"),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("line_total"))
    );

    private final JdbcTemplate jdbc;

    public PurchaseLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PurchaseLine> findByPurchaseBillId(long purchaseBillId) {
        return jdbc.query("SELECT * FROM purchase_line WHERE purchase_bill_id = ? ORDER BY id",
                MAPPER, purchaseBillId);
    }

    public Optional<PurchaseLine> findById(long id) {
        return jdbc.query("SELECT * FROM purchase_line WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long create(PurchaseLine l) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchase_line (purchase_bill_id, item_model_id, quantity, rate,
                            discount_amount, taxable_value, gst_rate, cgst_amount, sgst_amount,
                            igst_amount, line_total)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, l.purchaseBillId());
            ps.setLong(2, l.itemModelId());
            ps.setInt(3, l.quantity());
            ps.setLong(4, l.rate().paisa());
            ps.setLong(5, l.discountAmount().paisa());
            ps.setLong(6, l.taxableValue().paisa());
            ps.setBigDecimal(7, l.gstRate());
            ps.setLong(8, l.cgstAmount().paisa());
            ps.setLong(9, l.sgstAmount().paisa());
            ps.setLong(10, l.igstAmount().paisa());
            ps.setLong(11, l.lineTotal().paisa());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** Drafts are re-saved as a whole: delete-then-reinsert their lines rather than diffing. */
    public void deleteByPurchaseBillId(long purchaseBillId) {
        jdbc.update("DELETE FROM purchase_line WHERE purchase_bill_id = ?", purchaseBillId);
    }
}
