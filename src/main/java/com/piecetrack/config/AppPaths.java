package com.piecetrack.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the single directory tree all business data lives under, per NFR-03:
 * {@code %LOCALAPPDATA%\PieceTrack\}. Nothing this application writes belongs
 * under Program Files.
 * <p>
 * This is also the definition of "what a backup archive must contain" (FR-BAK-03):
 * everything under {@link #data()}, {@link #photos()} and {@link #invoices()}.
 * {@link #logs()} and {@link #config()} are deliberately excluded - see
 * docs/02-data-model.md section 6.
 */
@Component
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    private static final String APP_FOLDER_NAME = "PieceTrack";

    /** M15: this app shipped as "Furniture Shop Inventory Management" under this folder name
     *  before the generic rename - {@link #migrateLegacyRootIfNeeded()} moves an existing
     *  shop's data across on first launch after upgrading. */
    private static final String LEGACY_APP_FOLDER_NAME = "FurnitureIMS";

    private final Path root;

    public AppPaths() {
        this(resolveLocalAppData());
    }

    /** Package-visible constructor for tests that need an isolated root. */
    AppPaths(Path localAppData) {
        this.root = localAppData.resolve(APP_FOLDER_NAME);
        createDirectories(root, data(root), photos(root), invoices(root), backups(root), logs(root), config(root));
    }

    /**
     * Same root this class resolves, exposed statically so {@code Launcher} can compute the
     * log directory and set it as a system property before Logback initialises - which happens
     * before the Spring context exists, so the {@link AppPaths} bean is not available yet.
     */
    public static Path resolveRoot() {
        return resolveLocalAppData().resolve(APP_FOLDER_NAME);
    }

    /**
     * M15: moves a pre-rename shop's data from {@code %LOCALAPPDATA%\FurnitureIMS\} to
     * {@code %LOCALAPPDATA%\PieceTrack\}. Must run as the very first statement in {@code
     * Launcher.main} - before {@link #resolveRoot()} is even called for the Logback log
     * directory, since Logback creates {@code PieceTrack\logs\} the instant it opens its file
     * appender. That is why "already migrated" is judged by the database file existing under
     * the new root rather than the root directory merely existing: a naive existence check
     * would see Logback's own {@code logs\} folder and wrongly conclude there was nothing left
     * to do, handing the shop an empty database.
     * <p>
     * Moves, never copies-then-deletes: a copy that fails partway leaves two half-complete
     * databases with no way to tell which is authoritative. The primary path - the whole
     * legacy tree renamed onto the new location in one call - is an atomic directory rename
     * on the same volume (both are under the same {@code %LOCALAPPDATA%}), so it either fully
     * succeeds or fully fails; there is no partial-tree state to worry about there. The
     * defensive merge path (reached only if something unexpected already created the new root
     * before this ran) moves the business-data subdirectories in one at a time, with {@code
     * data\} - the one holding the "already migrated" sentinel - moved last, so a failure
     * partway through never leaves a new root that looks complete but is quietly missing
     * photos or invoices.
     * <p>
     * If a move fails outright, this logs the failure and returns with the legacy folder
     * intact and nothing lost - deliberately not attempted again within this same process.
     * The shop's data stays exactly where it was, this run starts against an empty new root
     * and therefore lands on the first-run setup wizard instead of the familiar login screen,
     * which is an impossible-to-miss signal that something needs attention rather than a
     * silent, easy-to-mistake-for-data-loss failure - and the very next launch retries this
     * method from scratch, since the sentinel check still finds no database under the new root.
     */
    public static void migrateLegacyRootIfNeeded() {
        migrateLegacyRootIfNeeded(resolveLocalAppData());
    }

    /** Package-visible overload so {@code AppPathsMigrationTest} can exercise this against an
     *  isolated temp directory instead of the real {@code %LOCALAPPDATA%}. */
    static void migrateLegacyRootIfNeeded(Path localAppData) {
        Path legacyRoot = localAppData.resolve(LEGACY_APP_FOLDER_NAME);
        if (!Files.isDirectory(legacyRoot)) {
            return;
        }
        Path newRoot = localAppData.resolve(APP_FOLDER_NAME);
        if (Files.exists(data(newRoot).resolve("app.db"))) {
            return;
        }
        try {
            if (!Files.exists(newRoot)) {
                Files.move(legacyRoot, newRoot);
                log.info("Migrated application data from the pre-rename location {} to {}.", legacyRoot, newRoot);
            } else {
                Files.createDirectories(newRoot);
                moveIfPresent(photos(legacyRoot), photos(newRoot));
                moveIfPresent(invoices(legacyRoot), invoices(newRoot));
                moveIfPresent(backups(legacyRoot), backups(newRoot));
                moveIfPresent(config(legacyRoot), config(newRoot));
                moveIfPresent(data(legacyRoot), data(newRoot));
                log.info("Merged application data from the pre-rename location {} into {}.", legacyRoot, newRoot);
            }
        } catch (IOException e) {
            log.error("Could not migrate application data from {} to {} - the pre-rename data is "
                    + "untouched and this will be retried automatically on the next launch.",
                    legacyRoot, newRoot, e);
        }
    }

    private static void moveIfPresent(Path source, Path destination) throws IOException {
        if (Files.exists(source)) {
            Files.move(source, destination);
        }
    }

    private static Path resolveLocalAppData() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            // Not on Windows, or the variable is missing - fall back to the user's home
            // directory so the app still runs (e.g. under a test harness or WSL).
            localAppData = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        return Path.of(localAppData);
    }

    private static void createDirectories(Path... paths) {
        try {
            for (Path p : paths) {
                Files.createDirectories(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create application data directories under " + paths[0], e);
        }
    }

    public Path root() {
        return root;
    }

    public Path data() {
        return data(root);
    }

    public Path databaseFile() {
        return data().resolve("app.db");
    }

    public Path photos() {
        return photos(root);
    }

    public Path invoices() {
        return invoices(root);
    }

    public Path backups() {
        return backups(root);
    }

    public Path logs() {
        return logs(root);
    }

    public Path config() {
        return config(root);
    }

    private static Path data(Path root) {
        return root.resolve("data");
    }

    private static Path photos(Path root) {
        return root.resolve("Photos");
    }

    private static Path invoices(Path root) {
        return root.resolve("Invoices");
    }

    private static Path backups(Path root) {
        return root.resolve("Backups");
    }

    private static Path logs(Path root) {
        return root.resolve("logs");
    }

    private static Path config(Path root) {
        return root.resolve("config");
    }
}
