package com.piecetrack.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15: {@link AppPaths#migrateLegacyRootIfNeeded(Path)} is the one thing standing between a
 * live shop and an apparent total-data-loss on upgrade (the folder rename from {@code
 * FurnitureIMS} to {@code PieceTrack}). No Spring context needed - this exercises the package-
 * private overload directly against isolated temp directories, exactly the way {@code
 * TestAppPathsFactory} gives tests access to the isolated-root constructor.
 */
class AppPathsMigrationTest {

    @Test
    void legacyFolderWithADatabaseIsMovedIntact() throws IOException {
        Path localAppData = Files.createTempDirectory("apppaths-migration-test-");
        Path legacyRoot = localAppData.resolve("FurnitureIMS");
        writeFile(legacyRoot.resolve("data").resolve("app.db"), "the shop's real data");
        writeFile(legacyRoot.resolve("Photos").resolve("1").resolve("photo.jpg"), "a photo");
        writeFile(legacyRoot.resolve("Invoices").resolve("INV-1.pdf"), "a pdf");

        AppPaths.migrateLegacyRootIfNeeded(localAppData);

        Path newRoot = localAppData.resolve("PieceTrack");
        assertEquals("the shop's real data", readFile(newRoot.resolve("data").resolve("app.db")));
        assertEquals("a photo", readFile(newRoot.resolve("Photos").resolve("1").resolve("photo.jpg")));
        assertEquals("a pdf", readFile(newRoot.resolve("Invoices").resolve("INV-1.pdf")));
        assertFalse(Files.exists(legacyRoot), "the legacy folder should be gone after a clean move, not copied");
    }

    @Test
    void newRootAlreadyHavingADatabaseIsANoOp() throws IOException {
        Path localAppData = Files.createTempDirectory("apppaths-migration-test-");
        Path legacyRoot = localAppData.resolve("FurnitureIMS");
        writeFile(legacyRoot.resolve("data").resolve("app.db"), "old data - must not be touched");
        Path newRoot = localAppData.resolve("PieceTrack");
        writeFile(newRoot.resolve("data").resolve("app.db"), "already-migrated data");

        AppPaths.migrateLegacyRootIfNeeded(localAppData);

        assertEquals("already-migrated data", readFile(newRoot.resolve("data").resolve("app.db")),
                "an already-populated new root must never be overwritten");
        assertEquals("old data - must not be touched", readFile(legacyRoot.resolve("data").resolve("app.db")),
                "the legacy folder is left alone once migration is already done");
    }

    @Test
    void newRootExistingWithOnlyLogsMergesTheRestIn() throws IOException {
        // Reproduces Logback creating PieceTrack\logs\ before the migration ever runs, if the
        // call-ordering guarantee in Launcher.main is ever accidentally broken.
        Path localAppData = Files.createTempDirectory("apppaths-migration-test-");
        Path legacyRoot = localAppData.resolve("FurnitureIMS");
        writeFile(legacyRoot.resolve("data").resolve("app.db"), "the shop's real data");
        writeFile(legacyRoot.resolve("Photos").resolve("1").resolve("photo.jpg"), "a photo");
        Path newRoot = localAppData.resolve("PieceTrack");
        Files.createDirectories(newRoot.resolve("logs"));

        AppPaths.migrateLegacyRootIfNeeded(localAppData);

        assertEquals("the shop's real data", readFile(newRoot.resolve("data").resolve("app.db")));
        assertEquals("a photo", readFile(newRoot.resolve("Photos").resolve("1").resolve("photo.jpg")));
    }

    @Test
    void noLegacyFolderIsANoOpThatCreatesNothing() throws IOException {
        Path localAppData = Files.createTempDirectory("apppaths-migration-test-");

        AppPaths.migrateLegacyRootIfNeeded(localAppData);

        assertFalse(Files.exists(localAppData.resolve("PieceTrack")),
                "a fresh install has no legacy folder to migrate from, and this must not invent one");
    }

    @Test
    void aRealAppPathsInstanceOpensCorrectlyAfterMigration() throws IOException {
        Path localAppData = Files.createTempDirectory("apppaths-migration-test-");
        writeFile(localAppData.resolve("FurnitureIMS").resolve("data").resolve("app.db"), "real db bytes");

        AppPaths.migrateLegacyRootIfNeeded(localAppData);
        AppPaths appPaths = TestAppPathsFactory.create(localAppData);

        assertTrue(Files.isDirectory(appPaths.data()));
        assertEquals("real db bytes", readFile(appPaths.databaseFile()));
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String readFile(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
