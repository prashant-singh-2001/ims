package com.piecetrack;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.BackupHistory;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.repository.AppSettingRepository;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.service.BackupScheduler;
import com.piecetrack.service.BackupService;
import com.piecetrack.service.CloudBackupProvider;
import com.piecetrack.service.SettingsService;
import com.piecetrack.service.UploadedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gap this closes: before this, {@code BackupService.runBackup} logged the UPLOAD_PENDING
 * and FAILED outcomes but not SUCCESS, and {@code BackupScheduler} said nothing at all when no
 * backup password was configured - so "every backup succeeded" and "no backup ever ran" were
 * both silence in the log. See both classes' own Javadoc for the fuller reasoning.
 * <p>
 * Follows the established pattern ({@code M8BackupTest}, {@code M12CloudProviderTest}): a real
 * temp-directory SQLite database via {@link TestAppPathsFactory}, no mocking of business logic
 * - only the network-facing {@link CloudBackupProvider} boundary is stubbed, the same boundary
 * {@code M8BackupTest}'s own class Javadoc explains can't use real credentials in this
 * environment. A Logback {@link ListAppender} on the {@code com.piecetrack} logger captures
 * what these tests assert on; nothing here reads or depends on the actual {@code ./logs} file.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(BackupLoggingTest.TestConfig.class)
class BackupLoggingTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-backup-logging-test-"));
        }

        /** A third {@link CloudBackupProvider} bean alongside the real Google/OneDrive ones,
         *  under an id neither of them uses - {@code CloudProviders} picks whichever bean's
         *  {@code id()} matches {@code settings.backup.provider}, so pointing that setting at
         *  {@value #FAKE_PROVIDER_ID} exercises the genuine SUCCESS path (a working upload)
         *  without needing real OAuth credentials, which do not exist in this environment. */
        @Bean
        CloudBackupProvider fakeSucceedingProvider() {
            return new CloudBackupProvider() {
                @Override
                public String id() {
                    return FAKE_PROVIDER_ID;
                }

                @Override
                public String displayName() {
                    return "Fake Test Provider";
                }

                @Override
                public boolean isConnected() {
                    return true;
                }

                @Override
                public void connect() {
                }

                @Override
                public void disconnect() {
                }

                @Override
                public UploadedFile upload(Path localFile, String remoteName) {
                    return new UploadedFile("fake-remote-id", remoteName, 1);
                }

                @Override
                public void download(String fileId, Path targetFile) {
                }

                @Override
                public void delete(String fileId) {
                }
            };
        }
    }

    static final String FAKE_PROVIDER_ID = "FAKE_TEST_PROVIDER";

    @Autowired private BackupService backupService;
    @Autowired private BackupScheduler backupScheduler;
    @Autowired private SettingsService settingsService;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private AppSettingRepository appSettingRepository;
    @Autowired private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUpShopProfileAndLogCapture() {
        // Same shape as M8BackupTest's own setup - a shop profile row so the backup pipeline
        // (which snapshots the whole database) isn't backing up an empty one.
        shopProfileRepository.save(new ShopProfile("Test Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));

        // All tests in this class share one Spring context and one on-disk database (same
        // reasoning M12CloudProviderTest documents for this exact setting) - reset back to
        // "unset" so aSuccessfulBackupEmitsACompletedOutcomeLine setting the fake provider
        // can't leak into a test that expects the uncredentialed, pending path.
        appSettingRepository.delete("backup.provider");

        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("com.piecetrack")).addAppender(appender);
    }

    @AfterEach
    void tearDownLogCapture() {
        ((Logger) LoggerFactory.getLogger("com.piecetrack")).detachAppender(appender);
    }

    @Test
    void aPendingBackupEmitsExactlyOneOutcomeLineNamingItsArchive() {
        BackupService.BackupOutcome outcome =
                backupService.runBackup(BackupHistory.BackupType.MANUAL, "shop-backup-password-123".toCharArray());
        assertEquals(BackupHistory.Status.UPLOAD_PENDING, outcome.status(),
                "no cloud provider configured for this run, so it must land pending, not silently succeed");

        String archiveName = archiveNameFor(outcome.historyId());
        long outcomeLines = messages().stream().filter(m -> m.contains(archiveName)).count();
        assertEquals(1, outcomeLines,
                "exactly one line should mention this backup's archive - the outcome line this change added, "
                        + "not zero (the old silent-on-success gap) and not two (logged from two places)");
        assertTrue(messages().stream().anyMatch(m -> m.contains(archiveName) && m.contains("not uploaded")),
                "the one outcome line should say the archive is pending, not merely mention its name in passing");
    }

    @Test
    void aSuccessfulBackupEmitsACompletedOutcomeLine() {
        settingsService.setBackupProvider(FAKE_PROVIDER_ID);

        BackupService.BackupOutcome outcome =
                backupService.runBackup(BackupHistory.BackupType.MANUAL, "shop-backup-password-123".toCharArray());
        assertEquals(BackupHistory.Status.SUCCESS, outcome.status(),
                "the fake provider's upload() always succeeds - this is the branch that previously logged nothing");

        String archiveName = archiveNameFor(outcome.historyId());
        assertTrue(messages().stream().anyMatch(m -> m.contains(archiveName) && m.contains("completed")),
                "a successful backup must produce its own outcome line - this is the exact gap this change closes");
    }

    /** NFR-10: "Logs shall never contain the login password, the backup password, or OAuth
     *  tokens." There is no automatic scrubbing (by the file's own design, see
     *  logback-spring.xml's header comment) - this is the one thing standing between that rule
     *  and a future call site quietly breaking it. */
    @Test
    void theBackupPasswordNeverReachesTheLog() {
        String sentinelPassword = "sentinel-super-secret-nfr10-password";
        backupService.runBackup(BackupHistory.BackupType.MANUAL, sentinelPassword.toCharArray());

        assertTrue(messages().stream().noneMatch(m -> m.contains(sentinelPassword)),
                "the backup password must never appear in a log line, in any form");
    }

    /** The regression this change could actually introduce: {@code checkSchedule()} runs
     *  every 60 seconds, so anything unguarded there would write 1,440 lines a day. This
     *  doesn't assume which call is "the first ever" (test method order isn't guaranteed, and
     *  every method in this class shares one Spring context and one {@code BackupScheduler}
     *  instance - the same reasoning {@code M12CloudProviderTest} documents for its own
     *  shared-state {@code @BeforeEach}); it only asserts that a second consecutive call in
     *  the same still-unconfigured state doesn't add another warning beyond whatever the
     *  first call in this test's capture window produced. */
    @Test
    void checkScheduleWithNoPasswordConfiguredDoesNotRepeatItsWarningOnASecondCall() {
        backupScheduler.checkSchedule();
        long afterFirst = countNoPasswordWarnings();

        backupScheduler.checkSchedule();
        long afterSecond = countNoPasswordWarnings();

        assertEquals(afterFirst, afterSecond,
                "a second consecutive check with the same (still unconfigured) state must not repeat the warning");
    }

    private long countNoPasswordWarnings() {
        return messages().stream().filter(m -> m.contains("No backup password configured")).count();
    }

    private String archiveNameFor(long historyId) {
        return jdbc.queryForObject("SELECT archive_name FROM backup_history WHERE id = ?", String.class, historyId);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
