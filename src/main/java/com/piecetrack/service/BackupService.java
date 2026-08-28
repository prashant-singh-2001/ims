package com.piecetrack.service;

import com.piecetrack.config.AppPaths;
import com.piecetrack.domain.BackupHistory;
import com.piecetrack.repository.BackupHistoryRepository;
import com.piecetrack.util.BackupEncryption;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The backup pipeline (FR-BAK-01..16), end to end: SQLite's own {@code VACUUM INTO} for a
 * consistent snapshot (FR-BAK-04 - never a raw file copy, which could catch a half-written
 * page while the app is open) + Photos/ + Invoices/ + manifest.json -&gt; ZIP -&gt;
 * {@link BackupEncryption} -&gt; upload -&gt; verify -&gt; prune -&gt; record outcome. A failed
 * upload (no internet) is recorded as {@code UPLOAD_PENDING}, never {@code FAILED} -
 * FR-BAK-12 treats "backed up locally, not yet uploaded" as a normal, expected state, not
 * an error.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    // Millisecond precision, not just seconds: two backups started within the same second
    // (a fast manual "Backup Now" click twice, or a test) must never collide on the same
    // local archive filename and silently overwrite each other.
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final int LOCAL_RETENTION_COUNT = 3;

    private final AppPaths appPaths;
    private final JdbcTemplate jdbc;
    private final BackupHistoryRepository backupHistoryRepository;
    private final CloudProviders cloudProviders;
    private final SettingsService settingsService;

    public BackupService(AppPaths appPaths, JdbcTemplate jdbc, BackupHistoryRepository backupHistoryRepository,
                          CloudProviders cloudProviders, SettingsService settingsService) {
        this.appPaths = appPaths;
        this.jdbc = jdbc;
        this.backupHistoryRepository = backupHistoryRepository;
        this.cloudProviders = cloudProviders;
        this.settingsService = settingsService;
    }

    /** {@code dbSha256} is the checksum of just the database snapshot, computed before
     *  the ZIP that contains this manifest is built (a manifest cannot record its own
     *  container's checksum). The plaintext ZIP's own checksum is a separate concern -
     *  it's what {@code backup_history.sha256} records, outside the archive entirely, for
     *  FR-BAK-13's post-download restore verification. */
    public record Manifest(String appVersion, int schemaVersion, String createdAt, String backupType,
                            String dbSha256) {
    }

    public record BackupOutcome(long historyId, BackupHistory.Status status, String message) {
    }

    /** FR-BAK-10 ("Backup now") calls this directly; the scheduler (milestone task) calls
     *  it for DAILY/WEEKLY runs. Runs synchronously - callers on the JavaFX Application
     *  Thread must run this on a background thread. */
    public BackupOutcome runBackup(BackupHistory.BackupType type, char[] backupPassword) {
        log.info("{} backup starting.", type);
        LocalDateTime startedAt = LocalDateTime.now();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("piecetrack-backup-");
            Path dbSnapshot = tempDir.resolve("app.db");
            snapshotDatabase(dbSnapshot);

            Path plainArchive = tempDir.resolve("archive.zip");
            Manifest manifest = new Manifest(appVersion(), currentSchemaVersion(), startedAt.toString(),
                    type.name(), sha256Of(dbSnapshot));
            buildZip(plainArchive, dbSnapshot, manifest);
            String sha256 = sha256Of(plainArchive);

            String archiveName = archiveFileName(type, startedAt);
            Path encryptedFile = appPaths.backups().resolve(archiveName);
            Files.createDirectories(appPaths.backups());
            BackupEncryption.encryptFile(plainArchive, encryptedFile, backupPassword);
            long sizeBytes = Files.size(encryptedFile);

            String remoteFileId = null;
            String provider = null;
            BackupHistory.Status status;
            String errorMessage = null;
            try {
                CloudBackupProvider active = cloudProviders.active();
                UploadedFile uploaded = active.upload(encryptedFile, archiveName);
                remoteFileId = uploaded.id();
                provider = active.id();
                status = BackupHistory.Status.SUCCESS;
            } catch (Exception e) {
                status = BackupHistory.Status.UPLOAD_PENDING;
                errorMessage = "Not yet uploaded: " + rootMessage(e);
            }

            LocalDateTime finishedAt = LocalDateTime.now();
            long id = backupHistoryRepository.create(new BackupHistory(0, type, startedAt, finishedAt, status,
                    archiveName, sizeBytes, sha256, remoteFileId, provider, encryptedFile.toString(), errorMessage));
            logOutcome(archiveName, sizeBytes, status, provider, errorMessage);

            pruneRetention();
            return new BackupOutcome(id, status, errorMessage);
        } catch (Exception e) {
            log.error("Backup failed", e);
            long id = backupHistoryRepository.create(new BackupHistory(0, type, startedAt, LocalDateTime.now(),
                    BackupHistory.Status.FAILED, null, null, null, null, null, null, rootMessage(e)));
            return new BackupOutcome(id, BackupHistory.Status.FAILED, rootMessage(e));
        } finally {
            if (tempDir != null) {
                deleteRecursivelyQuietly(tempDir);
            }
        }
    }

    /** One outcome line per backup, covering both terminal states {@link #runBackup} can
     *  return without a thrown exception - SUCCESS and UPLOAD_PENDING both belong here so
     *  neither can be logged from one place while the other drifts to another (NFR-10:
     *  "all backup activity"). The FAILED path is a genuine exception and keeps logging from
     *  its own catch block with the throwable attached, further down in {@link #runBackup}. */
    private void logOutcome(String archiveName, long sizeBytes, BackupHistory.Status status, String provider,
                             String errorMessage) {
        if (status == BackupHistory.Status.SUCCESS) {
            log.info("Backup {} completed and uploaded to {} ({} bytes).", archiveName, provider, sizeBytes);
        } else {
            log.info("Backup {} stored locally but not uploaded: {}", archiveName, errorMessage);
        }
    }

    /** Retries every archive still waiting on an upload (FR-BAK-12) - called when
     *  connectivity is confirmed to have returned. */
    public void retryPendingUploads() {
        for (BackupHistory pending : backupHistoryRepository.findByStatus(BackupHistory.Status.UPLOAD_PENDING)) {
            if (pending.localPath() == null || !Files.exists(Path.of(pending.localPath()))) {
                continue;
            }
            try {
                CloudBackupProvider active = cloudProviders.active();
                UploadedFile uploaded = active.upload(Path.of(pending.localPath()), pending.archiveName());
                backupHistoryRepository.updateOutcome(pending.id(), LocalDateTime.now(),
                        BackupHistory.Status.SUCCESS, uploaded.id(), active.id(), null);
                log.info("Pending backup {} uploaded to {}.", pending.archiveName(), active.id());
            } catch (Exception e) {
                log.info("Retry upload still pending for {}: {}", pending.archiveName(), rootMessage(e));
            }
        }
        pruneRetention();
    }

    // ---- Snapshot, archive, checksum --------------------------------------------------------

    private void snapshotDatabase(Path targetFile) {
        String escaped = targetFile.toString().replace("'", "''");
        jdbc.execute("VACUUM INTO '" + escaped + "'");
    }

    private void buildZip(Path zipFile, Path dbSnapshot, Manifest manifest) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            addFileEntry(out, dbSnapshot, "data/app.db");
            addDirectoryEntries(out, appPaths.photos(), "Photos");
            addDirectoryEntries(out, appPaths.invoices(), "Invoices");

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(gson.toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void addFileEntry(ZipOutputStream out, Path file, String entryName) throws IOException {
        out.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, out);
        out.closeEntry();
    }

    private static void addDirectoryEntries(ZipOutputStream out, Path directory, String entryPrefix)
            throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                addFileEntry(out, path, entryPrefix + "/" + relative);
            }
        }
    }

    /** Package-visible: {@code RestoreService} reuses this to verify a downloaded archive
     *  against {@code backup_history.sha256} before ever touching live data. */
    static String sha256Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new UncheckedIOException("Could not checksum " + file,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    private static String archiveFileName(BackupHistory.BackupType type, LocalDateTime startedAt) {
        // M15: renamed prefix affects only archives created from here on - restore always
        // reads the stored archive_name column rather than reconstructing this pattern, so a
        // pre-rename shop's existing "fims-..." archives keep resolving and restoring exactly
        // as before.
        return "piecetrack-" + startedAt.format(ARCHIVE_TIMESTAMP) + "-" + type.name().toLowerCase() + ".zip.enc";
    }

    private String appVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** The manifest's schema_version is what {@code RestoreService} compares against the
     *  running app's own migrations to satisfy FR-BAK-15. */
    int currentSchemaVersion() {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1", Integer.class);
        return max == null ? 0 : max;
    }

    // ---- Retention (FR-BAK-09/16) -----------------------------------------------------------

    private void pruneRetention() {
        int prunedCount = pruneByType(BackupHistory.BackupType.DAILY, settingsService.backupRetentionDaily())
                + pruneByType(BackupHistory.BackupType.WEEKLY, settingsService.backupRetentionWeekly())
                + pruneLocalCopies();
        // A count, not a line per archive (routine housekeeping shouldn't crowd out the
        // outcome lines above it) - and only when it actually did something.
        if (prunedCount > 0) {
            log.info("Backup retention pruned {} old archive record(s).", prunedCount);
        }
    }

    private int pruneByType(BackupHistory.BackupType type, int keepCount) {
        List<BackupHistory> successful = backupHistoryRepository
                .findByBackupTypeAndStatusOrderedByStartedDesc(type, BackupHistory.Status.SUCCESS);
        int prunedCount = 0;
        for (int i = keepCount; i < successful.size(); i++) {
            BackupHistory old = successful.get(i);
            if (old.remoteFileId() != null) {
                try {
                    cloudProviders.byId(old.provider()).delete(old.remoteFileId());
                } catch (Exception e) {
                    log.warn("Could not prune remote archive {}: {}", old.archiveName(), rootMessage(e));
                    continue;
                }
            }
            if (old.localPath() == null) {
                backupHistoryRepository.delete(old.id());
            } else {
                backupHistoryRepository.clearRemoteFileId(old.id());
            }
            prunedCount++;
        }
        return prunedCount;
    }

    private int pruneLocalCopies() {
        List<BackupHistory> withLocal = backupHistoryRepository.findAllOrderedByStartedDesc().stream()
                .filter(b -> b.localPath() != null)
                .toList();
        int prunedCount = 0;
        for (int i = LOCAL_RETENTION_COUNT; i < withLocal.size(); i++) {
            BackupHistory old = withLocal.get(i);
            try {
                Files.deleteIfExists(Path.of(old.localPath()));
            } catch (IOException e) {
                log.warn("Could not delete local archive {}: {}", old.localPath(), e.getMessage());
            }
            if (old.remoteFileId() == null) {
                backupHistoryRepository.delete(old.id());
            } else {
                backupHistoryRepository.updateLocalPath(old.id(), null);
            }
            prunedCount++;
        }
        return prunedCount;
    }

    private static void deleteRecursivelyQuietly(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort temp cleanup - a leftover temp file is not worth failing the backup over.
                }
            });
        } catch (IOException ignored) {
            // Same reasoning.
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
