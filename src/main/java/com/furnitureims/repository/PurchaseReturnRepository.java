package com.furnitureims.repository;

import com.furnitureims.domain.PurchaseReturn;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PurchaseReturnRepository {

    private static final RowMapper<PurchaseReturn> MAPPER = (rs, rowNum) -> new PurchaseReturn(
            rs.getLong("id"),
            rs.getLong("purchase_bill_id"),
            rs.getString("debit_note_no"),
            LocalDate.parse(rs.getString("return_date")),
            rs.getString("reason"),
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("total_amount")),
            rs.getString("pdf_path")
    );

    private final JdbcTemplate jdbc;

    public PurchaseReturnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PurchaseReturn> findById(long id) {
        return jdbc.query("SELECT * FROM purchase_return WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<PurchaseReturn> findByPurchaseBillId(long purchaseBillId) {
        return jdbc.query("SELECT * FROM purchase_return WHERE purchase_bill_id = ? ORDER BY return_date",
                MAPPER, purchaseBillId);
    }

    public long create(PurchaseReturn r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchase_return (purchase_bill_id, debit_note_no, return_date, reason,
                            taxable_value, cgst_amount, sgst_amount, igst_amount, total_amount, pdf_path)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.purchaseBillId());
            ps.setString(2, r.debitNoteNo());
            ps.setString(3, r.returnDate().toString());
            ps.setString(4, r.reason());
            ps.setLong(5, r.taxableValue().paisa());
            ps.setLong(6, r.cgstAmount().paisa());
            ps.setLong(7, r.sgstAmount().paisa());
            ps.setLong(8, r.igstAmount().paisa());
            ps.setLong(9, r.totalAmount().paisa());
            ps.setString(10, r.pdfPath());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** FR-DOC-02/05: overwrites the same row's path on regeneration. */
    public void updatePdfPath(long id, String pdfPath) {
        jdbc.update("UPDATE purchase_return SET pdf_path = ? WHERE id = ?", pdfPath, id);
    }
}
