package com.furnitureims.service;

import com.furnitureims.repository.AppSettingRepository;
import org.springframework.stereotype.Service;

/** Typed accessors over the generic app_setting key/value store (FR-SYS-02). */
@Service
public class SettingsService {

    private static final String IDLE_LOCK_MINUTES_KEY = "security.idle_lock_minutes";
    private static final int DEFAULT_IDLE_LOCK_MINUTES = 10;

    private final AppSettingRepository settings;

    public SettingsService(AppSettingRepository settings) {
        this.settings = settings;
    }

    /** FR-AUTH-04: default 10 minutes, valid range 1-120, or 0 meaning "never". */
    public int idleLockMinutes() {
        return settings.get(IDLE_LOCK_MINUTES_KEY)
                .map(Integer::parseInt)
                .orElse(DEFAULT_IDLE_LOCK_MINUTES);
    }

    public void setIdleLockMinutes(int minutes) {
        if (minutes < 0 || minutes > 120) {
            throw new IllegalArgumentException("Idle lock minutes must be between 0 (never) and 120");
        }
        settings.set(IDLE_LOCK_MINUTES_KEY, Integer.toString(minutes));
    }
}
