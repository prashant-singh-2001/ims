package com.furnitureims.repository;

import com.furnitureims.domain.ItemPhoto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

@Repository
public class ItemPhotoRepository {

    private static final RowMapper<ItemPhoto> MAPPER = (rs, rowNum) -> new ItemPhoto(
            rs.getLong("id"), rs.getLong("item_model_id"), rs.getString("relative_path"),
            rs.getInt("sort_order"), rs.getInt("is_primary") == 1);

    private final JdbcTemplate jdbc;

    public ItemPhotoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ItemPhoto> findByItemModelId(long itemModelId) {
        return jdbc.query("SELECT * FROM item_photo WHERE item_model_id = ? ORDER BY sort_order",
                MAPPER, itemModelId);
    }

    public long create(long itemModelId, String relativePath, int sortOrder, boolean primary) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO item_photo (item_model_id, relative_path, sort_order, is_primary) " +
                            "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, itemModelId);
            ps.setString(2, relativePath);
            ps.setInt(3, sortOrder);
            ps.setInt(4, primary ? 1 : 0);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void delete(long photoId) {
        jdbc.update("DELETE FROM item_photo WHERE id = ?", photoId);
    }

    @Transactional
    public void setPrimary(long itemModelId, long photoId) {
        jdbc.update("UPDATE item_photo SET is_primary = 0 WHERE item_model_id = ?", itemModelId);
        jdbc.update("UPDATE item_photo SET is_primary = 1 WHERE id = ?", photoId);
    }

    public void updateSortOrder(long photoId, int sortOrder) {
        jdbc.update("UPDATE item_photo SET sort_order = ? WHERE id = ?", sortOrder, photoId);
    }
}
