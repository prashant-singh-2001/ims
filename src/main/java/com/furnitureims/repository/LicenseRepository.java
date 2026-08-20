package com.furnitureims.repository;

import com.furnitureims.domain.License;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Singleton row, mirroring {@link ShopProfileRepository}'s {@code id = 1} shape. Timestamps
 * are stored as {@link Instant#toString()} (UTC, trailing {@code Z}) rather than the
 * {@code LocalDateTime}-without-zone convention the rest of this app uses for purely local
 * values (see {@code BackupHistoryRepository}) - a lease crosses a network boundary to a
 * server in an unknown timezone, so an unambiguous UTC instant is the only thing that is
 * ever safe to compare against {@code Instant.now()}.
 */
@Repository
public class LicenseRepository {

    private static final RowMapper<License> MAPPER = (rs, rowNum) -> new License(
            rs.getLong("id"),
            rs.getString("license_id"),
            rs.getString("activation_key"),
            rs.getString("shop_name"),
            rs.getString("fingerprint"),
            rs.getString("volume_serial"),
            rs.getString("lease_token"),
            parseInstant(rs.getString("lease_expires_at")),
            parseInstant(rs.getString("last_contact_at")),
            rs.getString("last_contact_result"),
            parseInstant(rs.getString("clock_watermark")),
            parseInstant(rs.getString("activated_at"))
    );

    private final JdbcTemplate jdbc;

    public LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM license WHERE id = 1", Integer.class);
        return count != null && count > 0;
    }

    public Optional<License> find() {
        return jdbc.query("SELECT * FROM license WHERE id = 1", MAPPER).stream().findFirst();
    }

    /** Upserts the single license row (id is always 1 - enforced by a CHECK constraint). */
    public void save(License license) {
        jdbc.update("""
                INSERT INTO license (id, license_id, activation_key, shop_name, fingerprint, volume_serial,
                        lease_token, lease_expires_at, last_contact_at, last_contact_result, clock_watermark,
                        activated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                        license_id = excluded.license_id,
                        activation_key = excluded.activation_key,
                        shop_name = excluded.shop_name,
                        fingerprint = excluded.fingerprint,
                        volume_serial = excluded.volume_serial,
                        lease_token = excluded.lease_token,
                        lease_expires_at = excluded.lease_expires_at,
                        last_contact_at = excluded.last_contact_at,
                        last_contact_result = excluded.last_contact_result,
                        clock_watermark = excluded.clock_watermark,
                        activated_at = excluded.activated_at
                """,
                license.licenseId(), license.activationKey(), license.shopName(), license.fingerprint(),
                license.volumeSerial(), license.leaseToken(),
                toText(license.leaseExpiresAt()), toText(license.lastContactAt()),
                license.lastContactResult(), toText(license.clockWatermark()), toText(license.activatedAt()));
    }

    private static Instant parseInstant(String text) {
        return text == null ? null : Instant.parse(text);
    }

    private static String toText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
