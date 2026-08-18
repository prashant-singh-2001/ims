package com.furnitureims.repository;

import com.furnitureims.domain.PiecePhoto;
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
public class PiecePhotoRepository {

    private static final RowMapper<PiecePhoto> MAPPER = (rs, rowNum) -> new PiecePhoto(
            rs.getLong("id"), rs.getLong("piece_id"), rs.getString("relative_path"),
            rs.getInt("sort_order"), rs.getInt("is_primary") == 1);

    private final JdbcTemplate jdbc;

    public PiecePhotoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PiecePhoto> findByPieceId(long pieceId) {
        return jdbc.query("SELECT * FROM piece_photo WHERE piece_id = ? ORDER BY sort_order",
                MAPPER, pieceId);
    }

    public long create(long pieceId, String relativePath, int sortOrder, boolean primary) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO piece_photo (piece_id, relative_path, sort_order, is_primary) " +
                            "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, pieceId);
            ps.setString(2, relativePath);
            ps.setInt(3, sortOrder);
            ps.setInt(4, primary ? 1 : 0);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void delete(long photoId) {
        jdbc.update("DELETE FROM piece_photo WHERE id = ?", photoId);
    }

    @Transactional
    public void setPrimary(long pieceId, long photoId) {
        jdbc.update("UPDATE piece_photo SET is_primary = 0 WHERE piece_id = ?", pieceId);
        jdbc.update("UPDATE piece_photo SET is_primary = 1 WHERE id = ?", photoId);
    }

    public void updateSortOrder(long photoId, int sortOrder) {
        jdbc.update("UPDATE piece_photo SET sort_order = ? WHERE id = ?", sortOrder, photoId);
    }
}
