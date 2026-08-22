package com.piecetrack.service;

import com.piecetrack.domain.BackupHistory;
import com.piecetrack.repository.BackupHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * FR-BAK-01/02/12: checks once a minute rather than using a static {@code @Scheduled(cron=)}
 * - the daily time and weekly day are user-configurable at runtime (Settings), which a
 * fixed cron expression can't be without restarting the app. Gated on
 * {@link SettingsService#hasBackupPassword()}: there is nothing this scheduler can do
 * before the owner has set one, whether that's still true at first run or - not
 * incidentally - in every automated test in this project, none of which configures a real
 * backup password, so the scheduler never fires during a test run either.
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

    public BackupScheduler(BackupService backupService, SettingsService settingsService,
                            BackupHistoryRepository backupHistoryRepository) {
        this.backupService = backupService;
        this.settingsService = settingsService;
        this.backupHistoryRepository = backupHistoryRepository;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void checkSchedule() {
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
