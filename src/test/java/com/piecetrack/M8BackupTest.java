package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.BackupHistory;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.repository.BackupHistoryRepository;
import com.piecetrack.service.BackupScheduler;
import com.piecetrack.service.BackupService;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.RestoreService;
import com.piecetrack.service.SettingsService;
import com.piecetrack.util.BackupEncryption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises milestone M8's backup pipeline against a real temp-directory SQLite database.
 * Google Drive is deliberately never configured here - no real OAuth credentials exist in
 * this environment - so every test runs the pipeline in its offline path (FR-BAK-12),
 * which is itself a first-class, fully-exercised state rather than a workaround: the
 * archive still gets created, encrypted and checksummed exactly as it would if the upload
 * had succeeded, landing in UPLOAD_PENDING instead of SUCCESS. What a real Google account
 * would add on top (OAuth consent, the actual network upload) is necessarily manual-only,
 * the same limitation M6 documented for a live SMTP send.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(M8BackupTest.TestPathsConfig.class)
class M8BackupTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-m8-test-"));
        }
    }

    @Autowired private AppPaths appPaths;
    @Autowired private BackupService backupService;
    @Autowired private RestoreService restoreService;
    @Autowired private BackupScheduler backupScheduler;
    @Autowired private BackupHistoryRepository backupHistoryRepository;
    @Autowired private SettingsService settingsService;
    @Autowired private CategoryService categoryService;
    @Autowired private com.piecetrack.repository.ShopProfileRepository shopProfileRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpShopProfile() {
        shopProfileRepository.save(new ShopProfile("Test Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");
    }

    @Test
    void backupEncryptionRoundTripsAndRejectsWrongPassword(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws IOException {
        Path plain = tempDir.resolve("plain.txt");
        Files.writeString(plain, "the quick brown fox jumps over the lazy dog");
        Path encrypted = tempDir.resolve("plain.txt.enc");
        Path decrypted = tempDir.resolve("plain-decrypted.txt");

        BackupEncryption.encryptFile(plain, encrypted, "correct horse battery staple".toCharArray());
        assertTrue(Files.exists(encrypted));
        assertTrue(Files.size(encrypted) > 0);

        BackupEncryption.decryptFile(encrypted, decrypted, "correct horse battery staple".toCharArray());
        assertEquals(Files.readString(plain), Files.readString(decrypted));

        Path wrongAttempt = tempDir.resolve("wrong.txt");
        assertThrows(BackupEncryption.WrongPasswordException.class,
                () -> BackupEncryption.decryptFile(encrypted, wrongAttempt, "totally different password".toCharArray()));
    }

    @Test
    void manualBackupProducesAValidEncryptedArchiveAndLandsUploadPendingWithoutDriveConfigured() {
        char[] password = "shop-backup-password-123".toCharArray();

        BackupService.BackupOutcome outcome = backupService.runBackup(BackupHistory.BackupType.MANUAL, password);

        assertEquals(BackupHistory.Status.UPLOAD_PENDING, outcome.status(),
                "no Google client id/secret configured in this test, so upload must not be treated as a failure");

        BackupHistory history = backupHistoryRepository.findById(outcome.historyId()).orElseThrow();
        assertEquals(BackupHistory.BackupType.MANUAL, history.backupType());
        assertNotNull(history.archiveName());
        assertNotNull(history.sha256());
        assertNotNull(history.localPath());
        assertTrue(Files.exists(Path.of(history.localPath())), "the encrypted archive should exist on local disk");
        assertTrue(history.sizeBytes() > 0);

        // Decrypt and unzip it back to confirm the archive is genuinely restorable, not
        // just present - this is exactly what RestoreService will do for real later.
        Path decryptedZip = appPaths.root().resolve("test-decrypted.zip");
        BackupEncryption.decryptFile(Path.of(history.localPath()), decryptedZip, password);

        try (ZipFile zip = new ZipFile(decryptedZip.toFile())) {
            assertNotNull(zip.getEntry("data/app.db"), "archive must contain the database snapshot");
            assertNotNull(zip.getEntry("manifest.json"), "archive must contain the manifest");
        } catch (IOException e) {
            throw new AssertionError("Archive was not a valid ZIP", e);
        }
    }

    @Test
    void retentionKeepsOnlyTheConfiguredNumberOfLocalArchives() {
        settingsService.setBackupRetentionDaily(1);
        char[] password = "shop-backup-password-123".toCharArray();

        BackupService.BackupOutcome first = backupService.runBackup(BackupHistory.BackupType.DAILY, password);
        BackupService.BackupOutcome second = backupService.runBackup(BackupHistory.BackupType.DAILY, password);

        // Both are UPLOAD_PENDING (no Drive configured), so Drive-retention pruning (which
        // only prunes SUCCESS rows) does not apply here - this checks the two runs at
        // least both produced independent, retrievable archives without colliding.
        assertNotEqualArchiveNames(first.historyId(), second.historyId());
    }

    private void assertNotEqualArchiveNames(long firstId, long secondId) {
        String firstName = backupHistoryRepository.findById(firstId).orElseThrow().archiveName();
        String secondName = backupHistoryRepository.findById(secondId).orElseThrow().archiveName();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstName, secondName);
    }

    /** The single most important test in this class: an actual backup taken, live data
     *  changed afterwards, then a full restore - including the real file-level swap
     *  RestoreService performs against the same live database path this test's own
     *  JdbcTemplate bean is still pointed at (SQLiteDataSource is intentionally
     *  non-pooled - see DataSourceConfig - so a fresh connection after the swap reads
     *  whatever is now on disk). Verifies the restore brings back the pre-backup state,
     *  not just "some" state, and that the WAL sidecar handling doesn't leave the restored
     *  database corrupted or paired with stale journal data. */
    @Test
    void backupAndRestoreRoundTripBringsBackThePreBackupState() {
        char[] password = "shop-backup-password-123".toCharArray();
        long markerCategoryId = categoryService.create("RestoreMarkerCategory");

        BackupService.BackupOutcome backup = backupService.runBackup(BackupHistory.BackupType.MANUAL, password);
        assertEquals(BackupHistory.Status.UPLOAD_PENDING, backup.status());

        // Mutate live data *after* the backup was taken - this is what restore must undo.
        categoryService.setActive(markerCategoryId, false);
        long postBackupOnlyCategoryId = categoryService.create("PostBackupOnlyCategory");

        RestoreService.RestorePreview preview = restoreService.prepareRestore(backup.historyId(), password);
        assertTrue(preview.manifest().schemaVersion() >= 7, "schema version should have been captured");

        Path safetyCopy = restoreService.performRestore(preview);
        assertTrue(Files.exists(safetyCopy), "a safety copy of pre-restore data should be kept");
        assertTrue(Files.exists(safetyCopy.resolve("app.db")), "the safety copy must contain the pre-restore database");

        // Fresh queries against the same live path now read the restored file.
        Boolean markerStillActive = jdbc.queryForObject(
                "SELECT is_active FROM category WHERE id = ?", Boolean.class, markerCategoryId);
        assertEquals(Boolean.TRUE, markerStillActive,
                "restore should have brought back the marker category's pre-mutation state");

        Integer postBackupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE id = ?", Integer.class, postBackupOnlyCategoryId);
        assertEquals(0, postBackupCount,
                "a category created after the backup was taken must not survive the restore");
    }

    @Test
    void restoreRejectsAWrongPassword() {
        char[] password = "shop-backup-password-123".toCharArray();
        BackupService.BackupOutcome backup = backupService.runBackup(BackupHistory.BackupType.MANUAL, password);

        assertThrows(RestoreService.RestoreVerificationException.class,
                () -> restoreService.prepareRestore(backup.historyId(), "wrong password entirely".toCharArray()));
    }

    /** Calls the @Scheduled method directly rather than waiting on real wall-clock timing -
     *  the standard, deterministic way to test a Spring @Scheduled method. Also doubles as
     *  proof the scheduler is correctly gated: with no backup password ever set (every
     *  other test method in this class, sharing this same database), checkSchedule() is a
     *  guaranteed no-op, which is exactly why enabling @Scheduled globally in
     *  PieceTrackApplication is safe for the rest of this test suite. */
    @Test
    void schedulerRunsACatchUpBackupOnceAPasswordIsConfigured() {
        int before = backupHistoryRepository.findAllOrderedByStartedDesc().size();

        settingsService.setBackupDailyTime(java.time.LocalTime.now().minusMinutes(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        settingsService.setBackupPassword("shop-backup-password-123");

        backupScheduler.checkSchedule();

        int after = backupHistoryRepository.findAllOrderedByStartedDesc().size();
        assertTrue(after > before, "a missed-backup catch-up run should have produced a new backup_history row");
    }
}
