package com.furnitureims.repository;

import com.furnitureims.domain.SalesReturnLine;
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
public class SalesReturnLineRepository {

    private static final RowMapper<SalesReturnLine> MAPPER = (rs, rowNum) -> new SalesReturnLine(
            rs.getLong("id"),
            rs.getLong("sales_return_id"),
            rs.getLong("piece_id"),
            rs.getLong("sales_line_id"),
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("line_total"))
    );

    private final JdbcTemplate jdbc;

    public SalesReturnLineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SalesReturnLine> findBySalesReturnId(long salesReturnId) {
        return jdbc.query("SELECT * FROM sales_return_line WHERE sales_return_id = ? ORDER BY id",
                MAPPER, salesReturnId);
    }

    /** FR-RPT-03: a returned sales_line must not still count as "sold" in a period profit
     *  report, even though the row itself is never deleted. */
    public boolean existsBySalesLineId(long salesLineId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_return_line WHERE sales_line_id = ?", Integer.class, salesLineId);
        return count != null && count > 0;
    }

    public long create(SalesReturnLine l) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sales_return_line (sales_return_id, piece_id, sales_line_id, taxable_value,
                            cgst_amount, sgst_amount, igst_amount, line_total)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, l.salesReturnId());
            ps.setLong(2, l.pieceId());
            ps.setLong(3, l.salesLineId());
            ps.setLong(4, l.taxableValue().paisa());
            ps.setLong(5, l.cgstAmount().paisa());
            ps.setLong(6, l.sgstAmount().paisa());
            ps.setLong(7, l.igstAmount().paisa());
            ps.setLong(8, l.lineTotal().paisa());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }
}
