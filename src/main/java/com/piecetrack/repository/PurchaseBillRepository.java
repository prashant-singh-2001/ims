package com.piecetrack.repository;

import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PurchaseBillRepository {

    private static final RowMapper<PurchaseBill> MAPPER = (rs, rowNum) -> new PurchaseBill(
            rs.getLong("id"),
            rs.getLong("supplier_id"),
            rs.getString("supplier_bill_no"),
            LocalDate.parse(rs.getString("bill_date")),
            LocalDate.parse(rs.getString("received_date")),
            rs.getInt("is_interstate") == 1,
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("freight")),
            Money.ofPaisa(rs.getLong("loading_charges")),
            Money.ofPaisa(rs.getLong("other_charges")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("round_off")),
            Money.ofPaisa(rs.getLong("grand_total")),
            PurchaseBill.Status.valueOf(rs.getString("status")),
            rs.getString("notes")
    );

    private final JdbcTemplate jdbc;

    public PurchaseBillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PurchaseBill> findById(long id) {
        return jdbc.query("SELECT * FROM purchase_bill WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public boolean existsBySupplierAndBillNo(long supplierId, String supplierBillNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_bill WHERE supplier_id = ? AND supplier_bill_no = ?",
                Integer.class, supplierId, supplierBillNo);
        return count != null && count > 0;
    }

    public long create(PurchaseBill b) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchase_bill (supplier_id, supplier_bill_no, bill_date, received_date,
                            is_interstate, taxable_value, freight, loading_charges, other_charges,
                            cgst_amount, sgst_amount, igst_amount, round_off, grand_total, status, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bind(ps, b);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void update(PurchaseBill b) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    UPDATE purchase_bill SET supplier_id = ?, supplier_bill_no = ?, bill_date = ?,
                            received_date = ?, is_interstate = ?, taxable_value = ?, freight = ?,
                            loading_charges = ?, other_charges = ?, cgst_amount = ?, sgst_amount = ?,
                            igst_amount = ?, round_off = ?, grand_total = ?, status = ?, notes = ?,
                            updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')
                    WHERE id = ?
                    """);
            int next = bind(ps, b);
            ps.setLong(next, b.id());
            return ps;
        });
    }

    private int bind(PreparedStatement ps, PurchaseBill b) throws java.sql.SQLException {
        int i = 1;
        ps.setLong(i++, b.supplierId());
        ps.setString(i++, b.supplierBillNo());
        ps.setString(i++, b.billDate().toString());
        ps.setString(i++, b.receivedDate().toString());
        ps.setInt(i++, b.interstate() ? 1 : 0);
        ps.setLong(i++, b.taxableValue().paisa());
        ps.setLong(i++, b.freight().paisa());
        ps.setLong(i++, b.loadingCharges().paisa());
        ps.setLong(i++, b.otherCharges().paisa());
        ps.setLong(i++, b.cgstAmount().paisa());
        ps.setLong(i++, b.sgstAmount().paisa());
        ps.setLong(i++, b.igstAmount().paisa());
        ps.setLong(i++, b.roundOff().paisa());
        ps.setLong(i++, b.grandTotal().paisa());
        ps.setString(i++, b.status().name());
        ps.setString(i++, b.notes());
        return i;
    }

    public void updateStatus(long id, PurchaseBill.Status status) {
        jdbc.update("UPDATE purchase_bill SET status = ?, " +
                "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?", status.name(), id);
    }

    /** Any piece created from this bill's lines that has left IN_STOCK - used by
     *  FR-PUR-08 to refuse a receipt reversal, naming what blocks it. */
    public List<String> tagsOfSoldOrMovedPieces(long purchaseBillId) {
        return jdbc.query("""
                SELECT p.tag FROM piece p
                JOIN purchase_line pl ON pl.id = p.purchase_line_id
                WHERE pl.purchase_bill_id = ? AND p.state != 'IN_STOCK'
                """, (rs, rowNum) -> rs.getString("tag"), purchaseBillId);
    }

    public List<PurchaseBillListRow> search(PurchaseBillSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT pb.*, s.name AS supplier_name
                FROM purchase_bill pb
                JOIN supplier s ON s.id = pb.supplier_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (criteria.supplierId() != null) {
            sql.append(" AND pb.supplier_id = ?");
            params.add(criteria.supplierId());
        }
        if (criteria.status() != null) {
            sql.append(" AND pb.status = ?");
            params.add(criteria.status().name());
        }
        if (criteria.billDateFrom() != null) {
            sql.append(" AND pb.bill_date >= ?");
            params.add(criteria.billDateFrom().toString());
        }
        if (criteria.billDateTo() != null) {
            sql.append(" AND pb.bill_date <= ?");
            params.add(criteria.billDateTo().toString());
        }
        sql.append(" ORDER BY pb.bill_date DESC, pb.id DESC");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new PurchaseBillListRow(
                MAPPER.mapRow(rs, rowNum), rs.getString("supplier_name")
        ), params.toArray());
    }
}
