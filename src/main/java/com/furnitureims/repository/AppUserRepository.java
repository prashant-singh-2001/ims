package com.furnitureims.repository;

import com.furnitureims.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Objects;

@Repository
public class AppUserRepository {

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("recovery_code_hash"),
            AppUser.Role.valueOf(rs.getString("role")),
            rs.getInt("is_active") == 1,
            rs.getString("last_login_at") == null ? null : LocalDateTime.parse(rs.getString("last_login_at"))
    );

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        return count != null && count > 0;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT * FROM app_user WHERE username = ?", MAPPER, username).stream().findFirst();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Creates the single OWNER row (FR-AUTH-01/07). Only one is ever created in v1 -
     *  {@link #exists()} gates the setup wizard so this cannot run twice. */
    public long create(String username, String passwordHash, String recoveryCodeHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO app_user (username, password_hash, recovery_code_hash, role, is_active) " +
                            "VALUES (?, ?, ?, 'OWNER', 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, recoveryCodeHash);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void updatePasswordHash(long userId, String newPasswordHash) {
        jdbc.update("UPDATE app_user SET password_hash = ?, " +
                        "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?",
                newPasswordHash, userId);
    }

    public void updateRecoveryCodeHash(long userId, String newRecoveryCodeHash) {
        jdbc.update("UPDATE app_user SET recovery_code_hash = ?, " +
                        "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?",
                newRecoveryCodeHash, userId);
    }

    public void recordLogin(long userId) {
        jdbc.update("UPDATE app_user SET last_login_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') " +
                "WHERE id = ?", userId);
    }
}
