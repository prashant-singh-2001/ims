package com.piecetrack.repository;

import com.piecetrack.domain.StorageLocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class StorageLocationRepository {

    private static final RowMapper<StorageLocation> MAPPER = (rs, rowNum) -> new StorageLocation(
            rs.getLong("id"), rs.getString("name"), rs.getInt("is_active") == 1);

    private final JdbcTemplate jdbc;

    public StorageLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StorageLocation> findAllActive() {
        return jdbc.query("SELECT * FROM storage_location WHERE is_active = 1 ORDER BY name", MAPPER);
    }

    public List<StorageLocation> findAll() {
        return jdbc.query("SELECT * FROM storage_location ORDER BY name", MAPPER);
    }

    public Optional<StorageLocation> findById(long id) {
        return jdbc.query("SELECT * FROM storage_location WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public boolean existsByName(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM storage_location WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    public long create(String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO storage_location (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void rename(long id, String newName) {
        jdbc.update("UPDATE storage_location SET name = ? WHERE id = ?", newName, id);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE storage_location SET is_active = ? WHERE id = ?", active ? 1 : 0, id);
    }
}
