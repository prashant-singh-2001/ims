package com.furnitureims.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only, per FR-SYS-03 - there is deliberately no update or delete method here, and
 * none should ever be added. The viewing screen itself is later scope; this is only the
 * write path that FR-PIECE-08 depends on now.
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long userId, String action, String entityType, Long entityId, String note) {
        jdbc.update("""
                INSERT INTO audit_log (user_id, action, entity_type, entity_id, note)
                VALUES (?, ?, ?, ?, ?)
                """, userId, action, entityType, entityId, note);
    }
}
