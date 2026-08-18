package com.furnitureims.repository;

import com.furnitureims.domain.BackupHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class BackupHistoryRepository {

    private static final RowMapper<BackupHistory> MAPPER = (rs, rowNum) -> new BackupHistory(
            rs.getLong("id"),
            BackupHistory.BackupType.valueOf(rs.getString("backup_type")),
            LocalDateTime.parse(rs.getString("started_at")),
            rs.getString("finished_at") == null ? null : LocalDateTime.parse(rs.getString("finished_at")),
            BackupHistory.Status.valueOf(rs.getString("status")),
            rs.getString("archive_name"),
            rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getString("remote_file_id"),
            rs.getString("provider"),
            rs.getString("local_path"),
            rs.getString("error_message")
    );

    private final JdbcTemplate jdbc;

    public BackupHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BackupHistory> findById(long id) {
        return jdbc.query("SELECT * FROM backup_history WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<BackupHistory> findAllOrderedByStartedDesc() {
        return jdbc.query("SELECT * FROM backup_history ORDER BY started_at DESC, id DESC", MAPPER);
    }

    public List<BackupHistory> findByBackupTypeAndStatusOrderedByStartedDesc(BackupHistory.BackupType type,
                                                                              BackupHistory.Status status) {
        return jdbc.query("SELECT * FROM backup_history WHERE backup_type = ? AND status = ? "
                + "ORDER BY started_at DESC, id DESC", MAPPER, type.name(), status.name());
    }

    public List<BackupHistory> findByStatus(BackupHistory.Status status) {
        return jdbc.query("SELECT * FROM backup_history WHERE status = ? ORDER BY started_at", MAPPER,
                status.name());
    }

    public Optional<BackupHistory> findMostRecentSuccessOrPending() {
        return jdbc.query("SELECT * FROM backup_history WHERE status IN ('SUCCESS', 'UPLOAD_PENDING') "
                + "ORDER BY started_at DESC, id DESC LIMIT 1", MAPPER).stream().findFirst();
    }

    public long create(BackupHistory b) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO backup_history (backup_type, started_at, finished_at, status, archive_name,
                            size_bytes, sha256, remote_file_id, provider, local_path, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, b.backupType().name());
            ps.setString(2, b.startedAt().toString());
            ps.setString(3, b.finishedAt() == null ? null : b.finishedAt().toString());
            ps.setString(4, b.status().name());
            ps.setString(5, b.archiveName());
            if (b.sizeBytes() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, b.sizeBytes());
            }
            ps.setString(7, b.sha256());
            ps.setString(8, b.remoteFileId());
            ps.setString(9, b.provider());
            ps.setString(10, b.localPath());
            ps.setString(11, b.errorMessage());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void updateOutcome(long id, LocalDateTime finishedAt, BackupHistory.Status status, String remoteFileId,
                               String provider, String errorMessage) {
        jdbc.update("""
                UPDATE backup_history SET finished_at = ?, status = ?, remote_file_id = ?, provider = ?,
                        error_message = ?
                WHERE id = ?
                """, finishedAt.toString(), status.name(), remoteFileId, provider, errorMessage, id);
    }

    public void updateLocalPath(long id, String localPath) {
        jdbc.update("UPDATE backup_history SET local_path = ? WHERE id = ?", localPath, id);
    }

    /** Called when retention pruning removes the remote copy of an archive that still has a
     *  local one - {@code provider} is cleared alongside it since it would otherwise describe
     *  a copy that no longer exists. */
    public void clearRemoteFileId(long id) {
        jdbc.update("UPDATE backup_history SET remote_file_id = NULL, provider = NULL WHERE id = ?", id);
    }

    /** Retention pruning (FR-BAK-09) and the archive list's own "Delete" action both call
     *  this - see {@link BackupHistory}'s class Javadoc for why a real delete is correct
     *  here, unlike everywhere else in this system. */
    public void delete(long id) {
        jdbc.update("DELETE FROM backup_history WHERE id = ?", id);
    }
}
