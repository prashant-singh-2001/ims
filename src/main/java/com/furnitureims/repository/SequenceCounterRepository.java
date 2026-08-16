package com.furnitureims.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs gap-tolerant, per-key counters - piece tags (FR-PIECE-02) today, invoice numbers
 * from milestone M4 onward. Unlike invoice numbering, piece tags don't need to be
 * gap-free, just unique, so a plain atomic increment is enough here.
 */
@Repository
public class SequenceCounterRepository {

    private final JdbcTemplate jdbc;

    public SequenceCounterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the value to use now; the counter is left pointing at the next one. */
    @Transactional
    public long next(String name, String scope) {
        jdbc.update("INSERT INTO sequence_counter (name, scope, next_value) VALUES (?, ?, 1) " +
                "ON CONFLICT(name, scope) DO NOTHING", name, scope);
        return jdbc.queryForObject(
                "UPDATE sequence_counter SET next_value = next_value + 1 WHERE name = ? AND scope = ? " +
                        "RETURNING next_value - 1",
                Long.class, name, scope);
    }
}
