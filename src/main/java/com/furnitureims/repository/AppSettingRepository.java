package com.furnitureims.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/** Backing store for FR-SYS-02's settings screen: one key/value row per setting. */
@Repository
public class AppSettingRepository {

    private final JdbcTemplate jdbc;

    public AppSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.query("SELECT value FROM app_setting WHERE key = ?",
                        (rs, rowNum) -> rs.getString("value"), key)
                .stream().filter(Objects::nonNull).findFirst();
    }

    public String getOrDefault(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public void set(String key, String value) {
        jdbc.update("""
                INSERT INTO app_setting (key, value, updated_at)
                VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
                ON CONFLICT(key) DO UPDATE SET
                        value = excluded.value,
                        updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')
                """, key, value);
    }

    /** Removes the row entirely, rather than {@link #set}ting a NULL value - a NULL-valued
     *  row would leave {@link #get} with a null element in its result list. */
    public void delete(String key) {
        jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
    }
}
