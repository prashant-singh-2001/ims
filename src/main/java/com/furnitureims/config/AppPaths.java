package com.furnitureims.config;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the single directory tree all business data lives under, per NFR-03:
 * {@code %LOCALAPPDATA%\FurnitureIMS\}. Nothing this application writes belongs
 * under Program Files.
 * <p>
 * This is also the definition of "what a backup archive must contain" (FR-BAK-03):
 * everything under {@link #data()}, {@link #photos()} and {@link #invoices()}.
 * {@link #logs()} and {@link #config()} are deliberately excluded - see
 * docs/02-data-model.md section 6.
 */
@Component
public class AppPaths {

    private static final String APP_FOLDER_NAME = "FurnitureIMS";

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
