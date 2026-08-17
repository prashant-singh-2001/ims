package com.furnitureims.service;

import com.furnitureims.config.AppPaths;
import com.furnitureims.domain.BackupHistory;
import com.furnitureims.repository.BackupHistoryRepository;
import com.furnitureims.util.BackupEncryption;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * FR-BAK-13/14/15: the slow, explicit restore flow docs/03-screens.md section 9 describes.
 * Split into two calls on purpose - {@link #prepareRestore} does every verification step
 * (download, decrypt, checksum, schema-version check, manifest read) against a staging
 * area that never touches live data, so the UI can show the archive's app/schema version
 * and get a typed "RESTORE" confirmation <em>before</em> {@link #performRestore} does
 * anything irreversible. Any verification failure in the first call leaves live data
 * completely untouched, trivially, since nothing has been touched yet.
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);
    private static final DateTimeFormatter SAFETY_COPY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    private final AppPaths appPaths;
    private final BackupHistoryRepository backupHistoryRepository;
    private final GoogleDriveService googleDriveService;
    private final BackupService backupService;
    private final AuditLogService auditLogService;

    public RestoreService(AppPaths appPaths, BackupHistoryRepository backupHistoryRepository,
                           GoogleDriveService googleDriveService, BackupService backupService,
                           AuditLogService auditLogService) {
        this.appPaths = appPaths;
        this.backupHistoryRepository = backupHistoryRepository;
        this.googleDriveService = googleDriveService;
        this.backupService = backupService;
        this.auditLogService = auditLogService;
    }

    /** FR-BAK-14: every failure in this flow throws this with a message naming exactly
     *  which step failed, never a raw stack trace. */
    public static class RestoreVerificationException extends RuntimeException {
        public RestoreVerificationException(String message) {
            super(message);
        }

        public RestoreVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record RestorePreview(long backupHistoryId, BackupService.Manifest manifest, Path stagedZip) {
    }

    public List<BackupHistory> listRestorableArchives() {
        return backupHistoryRepository.findAllOrderedByStartedDesc().stream()
                .filter(b -> b.status() == BackupHistory.Status.SUCCESS
                        || b.status() == BackupHistory.Status.UPLOAD_PENDING)
                .filter(b -> b.localPath() != null || b.driveFileId() != null)
                .toList();
    }

    /** Screens.md steps 1-4: download (if not already local) -&gt; decrypt -&gt; verify
     *  checksum -&gt; read the manifest. */
    public RestorePreview prepareRestore(long backupHistoryId, char[] password) {
        BackupHistory history = backupHistoryRepository.findById(backupHistoryId)
                .orElseThrow(() -> new IllegalArgumentException("Archive not found."));

        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory("furniture-ims-restore-");
        } catch (IOException e) {
            throw new RestoreVerificationException("Could not create a staging area to prepare the restore.", e);
        }

        Path encryptedFile = resolveEncryptedFile(history, stagingDir);
        Path decryptedZip = stagingDir.resolve("archive.zip");

        try {
            BackupEncryption.decryptFile(encryptedFile, decryptedZip, password);
        } catch (BackupEncryption.WrongPasswordException e) {
            throw new RestoreVerificationException("Incorrect password for this archive.", e);
        }

        if (history.sha256() != null) {
            String actual = BackupService.sha256Of(decryptedZip);
            if (!actual.equalsIgnoreCase(history.sha256())) {
                throw new RestoreVerificationException(
                        "Checksum verification failed - the archive may be corrupted or tampered with.");
            }
        }

        BackupService.Manifest manifest = readManifest(decryptedZip);
        int runningSchemaVersion = backupService.currentSchemaVersion();
        if (manifest.schemaVersion() > runningSchemaVersion) {
            throw new RestoreVerificationException("This archive was made with a newer version of the app "
                    + "(schema " + manifest.schemaVersion() + " vs. this app's " + runningSchemaVersion
                    + ") - update the application before restoring it.");
        }

        return new RestorePreview(backupHistoryId, manifest, decryptedZip);
    }

    /** Screens.md step 5: unpack the already-verified archive to a fresh staging folder,
     *  take a safety copy of current live data, then swap - live data is only ever touched
     *  after everything else has succeeded, and the safety copy is taken immediately
     *  before the swap so a failure during the swap itself can still roll back. The caller
     *  is responsible for having the owner restart the application afterwards (FR-BAK-13
     *  step 5/6) - this never restarts the JVM itself, since a mid-service exit is exactly
     *  the kind of surprise action this system avoids elsewhere too.
     *
     * @return the path of the safety copy, so the UI can tell the owner where their
     *         pre-restore data is kept. */
    public Path performRestore(RestorePreview preview) {
        Path unpackedDir;
        try {
            unpackedDir = Files.createTempDirectory("furniture-ims-restore-unpacked-");
            unzip(preview.stagedZip(), unpackedDir);
        } catch (IOException e) {
            throw new RestoreVerificationException("Could not unpack the verified archive.", e);
        }

        Path restoredDb = unpackedDir.resolve("data").resolve("app.db");
        Path restoredPhotos = unpackedDir.resolve("Photos");
        Path restoredInvoices = unpackedDir.resolve("Invoices");
        if (!Files.exists(restoredDb)) {
            throw new RestoreVerificationException("The archive did not contain a database file.");
        }

        String safetyTimestamp = LocalDateTime.now().format(SAFETY_COPY_TIMESTAMP);
        Path safetyCopyDir = appPaths.root().resolve("PreRestoreSafetyCopy-" + safetyTimestamp);

        try {
            Files.createDirectories(safetyCopyDir);
            moveDatabaseFileAndSidecars(appPaths.databaseFile(), safetyCopyDir.resolve("app.db"));
            moveIfExists(appPaths.photos(), safetyCopyDir.resolve("Photos"));
            moveIfExists(appPaths.invoices(), safetyCopyDir.resolve("Invoices"));
        } catch (IOException e) {
            throw new RestoreVerificationException(
                    "Could not safety-copy current data before restoring - nothing was changed.", e);
        }

        try {
            Files.createDirectories(appPaths.data());
            moveIfExists(restoredDb, appPaths.databaseFile());
            moveIfExists(restoredPhotos, appPaths.photos());
            moveIfExists(restoredInvoices, appPaths.invoices());
        } catch (IOException e) {
            log.error("Restore swap failed partway - rolling back from the safety copy.", e);
            rollBackFromSafetyCopy(safetyCopyDir);
            throw new RestoreVerificationException(
                    "Restoring failed while swapping in the new data - your previous data was rolled back. "
                            + "Nothing was lost.", e);
        }

        // FR-SYS-03: written *after* the swap, deliberately - the live database at this
        // point is the just-restored one (SQLiteDataSource is non-pooled, see
        // DataSourceConfig, so a fresh connection here reads/writes the new file), so this
        // becomes a permanent entry in the restored database's own audit trail recording
        // that it was restored, rather than being lost along with the pre-restore data it
        // would have landed in otherwise.
        auditLogService.record("RESTORE_PERFORMED", "BACKUP_HISTORY", preview.backupHistoryId(), null,
                java.util.Map.of("restoredAt", LocalDateTime.now().toString(),
                        "safetyCopyDir", safetyCopyDir.toString()));

        return safetyCopyDir;
    }

    private void rollBackFromSafetyCopy(Path safetyCopyDir) {
        try {
            moveDatabaseFileAndSidecars(safetyCopyDir.resolve("app.db"), appPaths.databaseFile());
            moveIfExists(safetyCopyDir.resolve("Photos"), appPaths.photos());
            moveIfExists(safetyCopyDir.resolve("Invoices"), appPaths.invoices());
        } catch (IOException e) {
            log.error("Automatic rollback also failed - previous data is still intact under {}", safetyCopyDir, e);
        }
    }

    private Path resolveEncryptedFile(BackupHistory history, Path stagingDir) {
        if (history.localPath() != null && Files.exists(Path.of(history.localPath()))) {
            return Path.of(history.localPath());
        }
        if (history.driveFileId() == null) {
            throw new RestoreVerificationException("This archive is no longer available locally or on Drive.");
        }
        Path downloaded = stagingDir.resolve("downloaded.zip.enc");
        try {
            googleDriveService.download(history.driveFileId(), downloaded);
        } catch (Exception e) {
            throw new RestoreVerificationException("Could not download the archive from Google Drive.", e);
        }
        return downloaded;
    }

    /** SQLite's WAL journal mode (see {@code DataSourceConfig}) leaves {@code -wal}/{@code
     *  -shm}/{@code -journal} sidecar files next to the main database file. Moving only
     *  {@code app.db} itself and leaving a stale sidecar behind at the live path would pair
     *  the freshly restored database with someone else's uncommitted WAL data the next
     *  time it's opened - so every sidecar moves (or is cleared away) in lockstep with the
     *  main file. The restored snapshot itself never has sidecars: {@code VACUUM INTO}
     *  always produces a single, fully checkpointed, standalone file. */
    private static void moveDatabaseFileAndSidecars(Path source, Path target) throws IOException {
        moveIfExists(source, target);
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            Path sourceSidecar = source.resolveSibling(source.getFileName() + suffix);
            Path targetSidecar = target.resolveSibling(target.getFileName() + suffix);
            moveIfExists(sourceSidecar, targetSidecar);
        }
    }

    private static void moveIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new IOException("Archive entry escapes the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static BackupService.Manifest readManifest(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) {
                throw new RestoreVerificationException("Archive is missing its manifest.");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new Gson().fromJson(json, BackupService.Manifest.class);
            }
        } catch (IOException e) {
            throw new RestoreVerificationException("Could not read the archive's manifest.", e);
        }
    }
}
