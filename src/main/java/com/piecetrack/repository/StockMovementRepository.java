package com.piecetrack.repository;

import com.piecetrack.domain.Piece;
import com.piecetrack.domain.StockMovement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Append-only, per docs/02-data-model.md section 3 - no update or delete method exists
 *  here, and none should be added. */
@Repository
public class StockMovementRepository {

    private static final RowMapper<StockMovement> MAPPER = (rs, rowNum) -> new StockMovement(
            rs.getLong("id"),
            rs.getLong("piece_id"),
            StockMovement.Type.valueOf(rs.getString("movement_type")),
            rs.getString("from_state") == null ? null : Piece.State.valueOf(rs.getString("from_state")),
            rs.getString("to_state") == null ? null : Piece.State.valueOf(rs.getString("to_state")),
            rs.getObject("from_location_id") == null ? null : rs.getLong("from_location_id"),
            rs.getObject("to_location_id") == null ? null : rs.getLong("to_location_id"),
            rs.getString("ref_type"),
            rs.getObject("ref_id") == null ? null : rs.getLong("ref_id"),
            LocalDateTime.parse(rs.getString("moved_at")),
            rs.getString("note")
    );

    private final JdbcTemplate jdbc;

    public StockMovementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long pieceId, StockMovement.Type type, Piece.State fromState, Piece.State toState,
                        Long fromLocationId, Long toLocationId, String refType, Long refId, String note) {
        jdbc.update("""
                INSERT INTO stock_movement (piece_id, movement_type, from_state, to_state,
                        from_location_id, to_location_id, ref_type, ref_id, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pieceId, type.name(),
                fromState == null ? null : fromState.name(),
                toState == null ? null : toState.name(),
                fromLocationId, toLocationId, refType, refId, note);
    }

    public List<StockMovement> findByPieceId(long pieceId) {
        return jdbc.query("SELECT * FROM stock_movement WHERE piece_id = ? ORDER BY moved_at, id",
                MAPPER, pieceId);
    }

    /** Paired with {@link PieceRepository#delete} for the one narrow hard-delete case in
     *  the system - see the comment there. */
    public void deleteByPieceId(long pieceId) {
        jdbc.update("DELETE FROM stock_movement WHERE piece_id = ?", pieceId);
    }
}
