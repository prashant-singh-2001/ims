package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NFR-09: "the application shall take an automatic local backup before applying any schema
 * migration" - and NFR-08's "upgrading the application never requires manual database
 * work". Both only mean something when tested against a database that already has data
 * from an older schema version, not a fresh install (where {@link com.piecetrack.config.FlywayConfig}
 * deliberately skips the safety copy - see its own javadoc).
 * <p>
 * This test seeds a temp-directory database at schema V6 - as if the shop had been running
 * a version of the app from before {@code backup_history}/V7 existed - using a standalone
 * Flyway instance pointed at the same JDBC URL {@code DataSourceConfig} would use, writes
 * one row of "existing data" an owner would recognize, then boots the real Spring context
 * (with {@link FlywayConfig}'s real {@code FlywayMigrationStrategy} bean, not a test double)
 * against that same directory and confirms all three things NFR-09/NFR-08 promise: every
 * pending migration (V7, V8, ...) applies automatically up to whatever the latest version
 * currently is, the pre-existing data survives untouched, and a pre-migration safety
 * snapshot was actually written to disk before the migration ran.
 * <p>
 * The expected final version below is a literal, not derived - it must be bumped every
 * time a new migration is added (most recently V12, M15's {@code custom_attributes}), the
 * same way {@code RouteCoverageTest} would need a new Route registered by hand. There is no
 * dynamic "latest migration" lookup elsewhere in the app to delegate to.
 */
class M9UpgradeTest {

    @Configuration
    static class FixedPathConfig {
        static Path fixedRoot;

        @Bean
        @Primary
        AppPaths testAppPaths() {
            return TestAppPathsFactory.create(fixedRoot);
        }
    }

    @Test
    void upgradingFromAnOlderSchemaMigratesAutomaticallyAndTakesASafetyBackupFirst() throws IOException {
        Path tempRoot = Files.createTempDirectory("piecetrack-upgrade-test-");
        AppPaths appPaths = TestAppPathsFactory.create(tempRoot);

        seedDatabaseAtSchemaVersion6(appPaths);
        long preExistingCategoryId = insertPreExistingCategory(appPaths);

        FixedPathConfig.fixedRoot = tempRoot;
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PieceTrackApplication.class)
                .sources(FixedPathConfig.class)
                .headless(true)
                .run();
        try {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            Integer appliedSchemaVersion = jdbc.queryForObject(
                    "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1",
                    Integer.class);
            assertEquals(12, appliedSchemaVersion,
                    "every pending migration up to the latest (V12) should have applied automatically on "
                            + "startup, with no manual database work (NFR-08)");

            String preExistingCategoryName = jdbc.queryForObject(
                    "SELECT name FROM category WHERE id = ?", String.class, preExistingCategoryId);
            assertEquals("PreExistingCategory", preExistingCategoryName,
                    "data from before the upgrade must survive the migration untouched");

            Path preMigrationDir = appPaths.backups().resolve("pre-migration");
            assertTrue(Files.isDirectory(preMigrationDir), "a pre-migration safety backup directory should exist");
            try (Stream<Path> files = Files.list(preMigrationDir)) {
                List<Path> snapshots = files.toList();
                assertFalse(snapshots.isEmpty(),
                        "a pre-migration safety snapshot should have been written before the pending "
                                + "migrations applied (NFR-09)");
                assertTrue(snapshots.get(0).getFileName().toString().endsWith(".db"));
                assertTrue(Files.size(snapshots.get(0)) > 0, "the safety snapshot must not be an empty file");
            }
        } finally {
            context.close();
        }
    }

    /** Mirrors {@code DataSourceConfig} exactly (WAL mode, foreign keys) but targets only
     *  V6 - simulating a shop's database as it was before this version of the app existed. */
    private static void seedDatabaseAtSchemaVersion6(AppPaths appPaths) {
        DataSource dataSource = rawDataSourceFor(appPaths);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("6")
                .load()
                .migrate();
    }

    private static long insertPreExistingCategory(AppPaths appPaths) {
        JdbcTemplate jdbc = new JdbcTemplate(rawDataSourceFor(appPaths));
        jdbc.update("INSERT INTO category (name, is_active) VALUES ('PreExistingCategory', 1)");
        return jdbc.queryForObject("SELECT id FROM category WHERE name = 'PreExistingCategory'", Long.class);
    }

    private static DataSource rawDataSourceFor(AppPaths appPaths) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + appPaths.databaseFile());
        return dataSource;
    }
}
