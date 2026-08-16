package com.furnitureims.repository;

import com.furnitureims.domain.Piece;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PieceRepository {

    private static final RowMapper<Piece> MAPPER = (rs, rowNum) -> new Piece(
            rs.getLong("id"),
            rs.getString("tag"),
            rs.getLong("item_model_id"),
            Piece.SourceType.valueOf(rs.getString("source_type")),
            rs.getObject("purchase_line_id") == null ? null : rs.getLong("purchase_line_id"),
            Money.ofPaisa(rs.getLong("landed_cost")),
            rs.getObject("location_id") == null ? null : rs.getLong("location_id"),
            Piece.State.valueOf(rs.getString("state")),
            rs.getString("state_reason"),
            LocalDate.parse(rs.getString("acquired_on")),
            LocalDateTime.parse(rs.getString("created_at")),
            LocalDateTime.parse(rs.getString("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public PieceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Piece> findById(long id) {
        return jdbc.query("SELECT * FROM piece WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Piece> findByTag(String tag) {
        return jdbc.query("SELECT * FROM piece WHERE tag = ?", MAPPER, tag).stream().findFirst();
    }

    public boolean existsByTag(String tag) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM piece WHERE tag = ?", Integer.class, tag);
        return count != null && count > 0;
    }

    public long create(Piece p) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO piece (tag, item_model_id, source_type, purchase_line_id, landed_cost,
                            location_id, state, state_reason, acquired_on)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.tag());
            ps.setLong(2, p.itemModelId());
            ps.setString(3, p.sourceType().name());
            if (p.purchaseLineId() == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setLong(4, p.purchaseLineId());
            }
            ps.setLong(5, p.landedCost().paisa());
            if (p.locationId() == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setLong(6, p.locationId());
            }
            ps.setString(7, p.state().name());
            ps.setString(8, p.stateReason());
            ps.setString(9, p.acquiredOn().toString());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void updateState(long id, Piece.State newState, String reason) {
        jdbc.update("UPDATE piece SET state = ?, state_reason = ?, " +
                        "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?",
                newState.name(), reason, id);
    }

    public void updateLocation(long id, Long newLocationId) {
        jdbc.update("UPDATE piece SET location_id = ?, " +
                        "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?",
                newLocationId, id);
    }

    public void renameTag(long id, String newTag) {
        jdbc.update("UPDATE piece SET tag = ?, " +
                "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?", newTag, id);
    }

    public long countInStock(long itemModelId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece WHERE item_model_id = ? AND state = 'IN_STOCK'",
                Long.class, itemModelId);
        return count == null ? 0 : count;
    }

    public List<PieceSummary> search(PieceSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.*, im.model_name, im.model_code, c.name AS category_name, sl.name AS location_name
                FROM piece p
                JOIN item_model im ON im.id = p.item_model_id
                JOIN category c ON c.id = im.category_id
                LEFT JOIN storage_location sl ON sl.id = p.location_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (criteria.tagOrModelSearch() != null && !criteria.tagOrModelSearch().isBlank()) {
            sql.append(" AND (p.tag LIKE ? OR im.model_name LIKE ? OR im.model_code LIKE ?)");
            String like = "%" + criteria.tagOrModelSearch().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (criteria.state() != null) {
            sql.append(" AND p.state = ?");
            params.add(criteria.state().name());
        }
        if (criteria.itemModelId() != null) {
            sql.append(" AND p.item_model_id = ?");
            params.add(criteria.itemModelId());
        }
        if (criteria.categoryId() != null) {
            sql.append(" AND im.category_id = ?");
            params.add(criteria.categoryId());
        }
        if (criteria.locationId() != null) {
            sql.append(" AND p.location_id = ?");
            params.add(criteria.locationId());
        }
        if (criteria.acquiredFrom() != null) {
            sql.append(" AND p.acquired_on >= ?");
            params.add(criteria.acquiredFrom().toString());
        }
        if (criteria.acquiredTo() != null) {
            sql.append(" AND p.acquired_on <= ?");
            params.add(criteria.acquiredTo().toString());
        }

        sql.append(" ORDER BY p.acquired_on DESC, p.tag");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new PieceSummary(
                MAPPER.mapRow(rs, rowNum),
                rs.getString("model_name"),
                rs.getString("model_code"),
                rs.getString("category_name"),
                rs.getString("location_name")
        ), params.toArray());
    }
}
