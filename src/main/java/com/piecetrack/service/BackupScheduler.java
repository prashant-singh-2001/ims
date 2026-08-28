package com.piecetrack.service;

import com.piecetrack.domain.BackupHistory;
import com.piecetrack.repository.BackupHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * FR-BAK-01/02/12: checks once a minute rather than using a static {@code @Scheduled(cron=)}
 * - the daily time and weekly day are user-configurable at runtime (Settings), which a
 * fixed cron expression can't be without restarting the app. The actual backup-running body
 * is gated on {@link SettingsService#hasBackupPassword()}: there is nothing it can do before
 * the owner has set one, whether that's still true at first run or - not incidentally - in
 * every automated test in this project, none of which configures a real backup password, so
 * that part never fires during a test run either. The two "break the silence" checks below
 * run <em>before</em> that gate, deliberately - an unconfigured shop is exactly the case
 * worth reporting, not one to gate into invisibility.
 */
@Service
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalTime DEFAULT_TIME = LocalTime.of(21, 30);

    private final BackupService backupService;
    private final SettingsService settingsService;
    private final BackupHistoryRepository backupHistoryRepository;

    private LocalDate lastDailyRunDate;
    private LocalDate lastWeeklyRunDate;
    private boolean startupCatchUpChecked;
    private boolean startupHealthReported;
    // null until the first check - lets reportPasswordStateIfChanged tell "first time we've
    // ever looked, and it's unconfigured" (warn) apart from "it flipped since last check"
    // (also worth a line) from "unchanged since last check" (say nothing, this runs every
    // 60 seconds and unguarded logging here is 1,440 lines a day).
    private Boolean lastPasswordConfigured;

    public BackupScheduler(BackupService backupService, SettingsService settingsService,
                            BackupHistoryRepository backupHistoryRepository) {
        this.backupService = backupService;
        this.settingsService = settingsService;
        this.backupHistoryRepository = backupHistoryRepository;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void checkSchedule() {
        if (!startupHealthReported) {
            reportBackupHealth();
            startupHealthReported = true;
        }
        reportPasswordStateIfChanged();
        if (!settingsService.hasBackupPassword()) {
            return;
        }
        if (!startupCatchUpChecked) {
            runCatchUpIfNeeded();
            startupCatchUpChecked = true;
        }
        runScheduledIfDue();
        backupService.retryPendingUploads();
    }

    /** Once per app session: states plainly whether this PC has ever completed a backup,
     *  so "healthy and quiet" is never indistinguishable from "never configured" by reading
     *  the log alone. Reuses {@link BackupHistoryRepository#findMostRecentSuccessOrPending()}
     *  - {@link #runCatchUpIfNeeded()} already queries this exact thing, just later in the
     *  same check, for a different purpose (deciding whether to run one, not reporting it). */
    private void reportBackupHealth() {
        Optional<BackupHistory> last = backupHistoryRepository.findMostRecentSuccessOrPending();
        if (last.isEmpty()) {
            log.warn("No successful backup has ever completed on this PC.");
            return;
        }
        BackupHistory backup = last.get();
        long hoursAgo = Duration.between(backup.startedAt(), LocalDateTime.now()).toHours();
        log.info("Most recent backup: {} ({}), started {} ({} hour(s) ago).", backup.archiveName(), backup.status(),
                backup.startedAt(), hoursAgo);
    }

    /** Logs only on a real transition, so an unattended shop's log doesn't repeat the same
     *  complaint 1,440 times a day: unconfigured is warned on the first check of this session
     *  and again on any later flip back to unconfigured, but a first check that finds it
     *  already configured says nothing (that's the healthy, expected case - and
     *  {@link #reportBackupHealth()} already speaks to overall health once above). */
    private void reportPasswordStateIfChanged() {
        boolean configured = settingsService.hasBackupPassword();
        if (lastPasswordConfigured != null && lastPasswordConfigured == configured) {
            return;
        }
        if (configured) {
            if (lastPasswordConfigured != null) {
                log.info("Backup password is now configured - scheduled backups can run unattended.");
            }
        } else {
            log.warn("No backup password configured - scheduled backups cannot run until one is set "
                    + "(Settings > Backup).");
        }
        lastPasswordConfigured = configured;
    }

    private void runScheduledIfDue() {
        LocalTime scheduledTime = parseTime(settingsService.backupDailyTime());
        LocalDateTime now = LocalDateTime.now();
        if (now.toLocalTime().isBefore(scheduledTime)) {
            return;
        }
        LocalDate today = now.toLocalDate();
        if (!today.equals(lastDailyRunDate)) {
            runBackup(BackupHistory.BackupType.DAILY);
            lastDailyRunDate = today;
        }

        DayOfWeek weeklyDay = parseDay(settingsService.backupWeeklyDay());
        if (now.getDayOfWeek() == weeklyDay && !today.equals(lastWeeklyRunDate)) {
            runBackup(BackupHistory.BackupType.WEEKLY);
            lastWeeklyRunDate = today;
        }
    }

    /** FR-BAK-02: the shop PC being off at the scheduled hour is the normal case, not an
     *  edge case - catch up shortly after this launch instead of silently waiting for the
     *  next scheduled slot, possibly a full day away. */
    private void runCatchUpIfNeeded() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todaysScheduledRun = LocalDateTime.of(now.toLocalDate(), parseTime(settingsService.backupDailyTime()));

        Optional<BackupHistory> last = backupHistoryRepository.findMostRecentSuccessOrPending();
        boolean missed = last.isEmpty()
                ? now.isAfter(todaysScheduledRun)
                : last.get().startedAt().isBefore(todaysScheduledRun.minusDays(1));

        if (missed) {
            log.info("Missed scheduled backup detected on startup - running a catch-up backup now.");
            runBackup(BackupHistory.BackupType.DAILY);
            lastDailyRunDate = now.toLocalDate();
        }
    }

    private void runBackup(BackupHistory.BackupType type) {
        settingsService.currentBackupPasswordForScheduledRun().ifPresentOrElse(
                password -> backupService.runBackup(type, password.toCharArray()),
                () -> log.warn("Scheduled {} backup skipped - no backup password available for unattended use.",
                        type));
    }

    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text, TIME_FORMAT);
        } catch (RuntimeException e) {
            return DEFAULT_TIME;
        }
    }

    private static DayOfWeek parseDay(String text) {
        try {
            return DayOfWeek.valueOf(text);
        } catch (RuntimeException e) {
            return DayOfWeek.SUNDAY;
        }
    }
}
