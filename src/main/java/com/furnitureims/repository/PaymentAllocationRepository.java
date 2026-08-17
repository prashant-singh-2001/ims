package com.furnitureims.repository;

import com.furnitureims.domain.PaymentAllocation;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class PaymentAllocationRepository {

    private static final RowMapper<PaymentAllocation> MAPPER = (rs, rowNum) -> new PaymentAllocation(
            rs.getLong("id"),
            rs.getLong("payment_id"),
            PaymentAllocation.TargetType.valueOf(rs.getString("target_type")),
            rs.getLong("target_id"),
            Money.ofPaisa(rs.getLong("amount"))
    );

    private final JdbcTemplate jdbc;

    public PaymentAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PaymentAllocation> findByPaymentId(long paymentId) {
        return jdbc.query("SELECT * FROM payment_allocation WHERE payment_id = ? ORDER BY id", MAPPER, paymentId);
    }

    public long create(PaymentAllocation a) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO payment_allocation (payment_id, target_type, target_id, amount)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, a.paymentId());
            ps.setString(2, a.targetType().name());
            ps.setLong(3, a.targetId());
            ps.setLong(4, a.amount().paisa());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** SUM of allocations to a target from payments that are not soft-deleted - the
     *  "amount paid" half of every balance formula in the system. */
    public Money sumByTarget(PaymentAllocation.TargetType targetType, long targetId) {
        Long paisa = jdbc.queryForObject("""
                SELECT COALESCE(SUM(pa.amount), 0)
                FROM payment_allocation pa
                JOIN payment p ON p.id = pa.payment_id
                WHERE pa.target_type = ? AND pa.target_id = ? AND p.is_deleted = 0
                """, Long.class, targetType.name(), targetId);
        return Money.ofPaisa(paisa == null ? 0 : paisa);
    }

    /** Bulk equivalent of {@link #sumByTarget} across every target of one type at once - a
     *  single aggregate query instead of one per target, needed to keep FR-RPT-04's dues
     *  report within its NFR-04 budget at 20,000-invoice scale. A target with no payments
     *  is simply absent from the map, meaning zero paid. */
    public Map<Long, Money> sumByTargetType(PaymentAllocation.TargetType targetType) {
        Map<Long, Money> result = new HashMap<>();
        jdbc.query("""
                SELECT pa.target_id AS target_id, SUM(pa.amount) AS total
                FROM payment_allocation pa
                JOIN payment p ON p.id = pa.payment_id
                WHERE pa.target_type = ? AND p.is_deleted = 0
                GROUP BY pa.target_id
                """, rs -> {
            result.put(rs.getLong("target_id"), Money.ofPaisa(rs.getLong("total")));
        }, targetType.name());
        return result;
    }

    /** FR-SAL-11: undoes the link between a cancelled invoice and any payments allocated
     *  to it - the payment rows themselves are untouched (real money was really received),
     *  only the now-meaningless target link is removed, freeing that amount to be
     *  reallocated or refunded. */
    public void deleteByTarget(PaymentAllocation.TargetType targetType, long targetId) {
        jdbc.update("DELETE FROM payment_allocation WHERE target_type = ? AND target_id = ?",
                targetType.name(), targetId);
    }
}
