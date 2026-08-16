package com.furnitureims.repository;

import com.furnitureims.domain.PurchaseReturnLine;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

@Repository
public class PurchaseReturnLineRepository {

    private static final RowMapper<PurchaseReturnLine> MAPPER = (rs, rowNum) -> new PurchaseReturnLine(
            rs.getLong("id"),
            rs.getLong("purchase_return_id"),
            rs.getLong("piece_id"),
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("line_total"))
    );

    private final JdbcTemplate jdbc;

    public PurchaseReturnLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PurchaseReturnLine> findByPurchaseReturnId(long purchaseReturnId) {
        return jdbc.query("SELECT * FROM purchase_return_line WHERE purchase_return_id = ? ORDER BY id",
                MAPPER, purchaseReturnId);
    }

    public boolean existsByPieceId(long pieceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_return_line WHERE piece_id = ?", Integer.class, pieceId);
        return count != null && count > 0;
    }

    public long create(PurchaseReturnLine l) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchase_return_line (purchase_return_id, piece_id, taxable_value,
                            cgst_amount, sgst_amount, igst_amount, line_total)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, l.purchaseReturnId());
            ps.setLong(2, l.pieceId());
            ps.setLong(3, l.taxableValue().paisa());
            ps.setLong(4, l.cgstAmount().paisa());
            ps.setLong(5, l.sgstAmount().paisa());
            ps.setLong(6, l.igstAmount().paisa());
            ps.setLong(7, l.lineTotal().paisa());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }
}
