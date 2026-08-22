package com.piecetrack.repository;

import com.piecetrack.domain.AttributeDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/** Mirrors {@link CategoryRepository}'s shape - a small user-managed lookup list, ordered by
 *  {@code display_order} rather than name, since that order is what the item model editor's
 *  form actually renders top to bottom (M15). */
@Repository
public class AttributeDefinitionRepository {

    private static final RowMapper<AttributeDefinition> MAPPER = (rs, rowNum) -> new AttributeDefinition(
            rs.getLong("id"), rs.getString("name"), rs.getInt("display_order"), rs.getInt("is_active") == 1);

    private final JdbcTemplate jdbc;

    public AttributeDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AttributeDefinition> findAllActive() {
        return jdbc.query(
                "SELECT * FROM attribute_definition WHERE is_active = 1 ORDER BY display_order, name", MAPPER);
    }

    public List<AttributeDefinition> findAll() {
        return jdbc.query("SELECT * FROM attribute_definition ORDER BY display_order, name", MAPPER);
    }

    public boolean existsByName(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attribute_definition WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    public long create(String name, int displayOrder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO attribute_definition (name, display_order) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, displayOrder);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void rename(long id, String newName) {
        jdbc.update("UPDATE attribute_definition SET name = ? WHERE id = ?", newName, id);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE attribute_definition SET is_active = ? WHERE id = ?", active ? 1 : 0, id);
    }
}
