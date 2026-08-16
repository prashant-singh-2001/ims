package com.furnitureims.repository;

import com.furnitureims.domain.Payment;
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
public class PaymentRepository {

    private static final RowMapper<Payment> MAPPER = (rs, rowNum) -> new Payment(
            rs.getLong("id"),
            Payment.Direction.valueOf(rs.getString("direction")),
            Payment.PartyType.valueOf(rs.getString("party_type")),
            rs.getLong("party_id"),
            LocalDate.parse(rs.getString("payment_date")),
            Money.ofPaisa(rs.getLong("amount")),
            Payment.Mode.valueOf(rs.getString("mode")),
            rs.getString("reference_no"),
            rs.getString("note"),
            rs.getInt("is_deleted") == 1,
            rs.getString("deleted_reason"),
            rs.getString("deleted_at") == null ? null : LocalDateTime.parse(rs.getString("deleted_at"))
    );

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Payment> findById(long id) {
        return jdbc.query("SELECT * FROM payment WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long create(Payment p) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO payment (direction, party_type, party_id, payment_date, amount, mode,
                            reference_no, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.direction().name());
            ps.setString(2, p.partyType().name());
            ps.setLong(3, p.partyId());
            ps.setString(4, p.paymentDate().toString());
            ps.setLong(5, p.amount().paisa());
            ps.setString(6, p.mode().name());
            ps.setString(7, p.referenceNo());
            ps.setString(8, p.note());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void softDelete(long id, String reason, LocalDateTime deletedAt) {
        jdbc.update("UPDATE payment SET is_deleted = 1, deleted_reason = ?, deleted_at = ? WHERE id = ?",
                reason, deletedAt.toString(), id);
    }

    public List<Payment> search(PaymentSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("SELECT * FROM payment WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (!criteria.includeDeleted()) {
            sql.append(" AND is_deleted = 0");
        }
        if (criteria.partyType() != null) {
            sql.append(" AND party_type = ?");
            params.add(criteria.partyType().name());
        }
        if (criteria.partyId() != null) {
            sql.append(" AND party_id = ?");
            params.add(criteria.partyId());
        }
        if (criteria.mode() != null) {
            sql.append(" AND mode = ?");
            params.add(criteria.mode().name());
        }
        if (criteria.dateFrom() != null) {
            sql.append(" AND payment_date >= ?");
            params.add(criteria.dateFrom().toString());
        }
        if (criteria.dateTo() != null) {
            sql.append(" AND payment_date <= ?");
            params.add(criteria.dateTo().toString());
        }
        sql.append(" ORDER BY payment_date DESC, id DESC");

        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }
}
