package com.furnitureims.repository;

import com.furnitureims.domain.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, per FR-SYS-03 - there is deliberately no update or delete method here, and
 * none should ever be added.
 */
@Repository
public class AuditLogRepository {

    private static final RowMapper<AuditLog> MAPPER = (rs, rowNum) -> new AuditLog(
            rs.getLong("id"),
            LocalDateTime.parse(rs.getString("logged_at")),
            rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
            rs.getString("username"),
            rs.getString("action"),
            rs.getString("entity_type"),
            rs.getObject("entity_id") == null ? null : rs.getLong("entity_id"),
            rs.getString("before_json"),
            rs.getString("after_json"),
            rs.getString("note")
    );

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long userId, String action, String entityType, Long entityId, String note) {
        record(userId, action, entityType, entityId, null, null, note);
    }

    public void record(Long userId, String action, String entityType, Long entityId,
                        String beforeJson, String afterJson, String note) {
        jdbc.update("""
                INSERT INTO audit_log (user_id, action, entity_type, entity_id, before_json, after_json, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, action, entityType, entityId, beforeJson, afterJson, note);
    }

    /** Viewable and exportable per FR-SYS-03 - newest first, capped so a long-running shop's
     *  history can't stall the screen; the CSV export button re-runs the same query with no
     *  cap when the owner actually wants everything. */
    public List<AuditLog> search(AuditLogSearchCriteria criteria, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.*, u.username AS username
                FROM audit_log a
                LEFT JOIN app_user u ON u.id = a.user_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (criteria.dateFrom() != null) {
            sql.append(" AND a.logged_at >= ?");
            params.add(criteria.dateFrom().toString());
        }
        if (criteria.dateTo() != null) {
            sql.append(" AND a.logged_at < ?");
            params.add(criteria.dateTo().plusDays(1).toString());
        }
        if (criteria.action() != null && !criteria.action().isBlank()) {
            sql.append(" AND a.action = ?");
            params.add(criteria.action());
        }
        if (criteria.entityType() != null && !criteria.entityType().isBlank()) {
            sql.append(" AND a.entity_type = ?");
            params.add(criteria.entityType());
        }
        sql.append(" ORDER BY a.logged_at DESC, a.id DESC");
        if (limit > 0) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }

        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    /** Feeds the action-filter dropdown - distinct action names actually used so far,
     *  rather than a hard-coded list that would drift from the real call sites. */
    public List<String> distinctActions() {
        return jdbc.queryForList("SELECT DISTINCT action FROM audit_log ORDER BY action", String.class);
    }
}
