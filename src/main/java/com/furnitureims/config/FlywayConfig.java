package com.furnitureims.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * NFR-09: "the application shall take an automatic local backup before applying any schema
 * migration." Only fires on a real upgrade - {@link MigrationInfoService#applied()}
 * non-empty means this database has been through at least one migration before, so a
 * non-empty {@link MigrationInfoService#pending()} on top of that means an upgrade is about
 * to touch live data. A brand-new install has nothing applied yet, so there is nothing
 * meaningful to protect and no safety copy is taken.
 * <p>
 * The copy is a plain {@code VACUUM INTO} snapshot - same technique {@code BackupService}
 * uses for FR-BAK-04 - written straight to local disk, unencrypted: this is a five-minute
 * emergency undo for "the upgrade went wrong", not a retained, off-site backup, so it is
 * deliberately kept simple and out of the encrypted Drive pipeline entirely.
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Bean
    public FlywayMigrationStrategy preMigrationSafetyBackupStrategy(AppPaths appPaths) {
        return flyway -> {
            MigrationInfoService info = flyway.info();
            boolean isUpgradeOverLiveData = info.applied().length > 0 && info.pending().length > 0;
            if (isUpgradeOverLiveData) {
                takeSafetyBackup(flyway, appPaths);
            }
            flyway.migrate();
        };
    }

    private void takeSafetyBackup(Flyway flyway, AppPaths appPaths) {
        try {
            Path targetDir = appPaths.backups().resolve("pre-migration");
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve("pre-migration-" + LocalDateTime.now().format(TIMESTAMP) + ".db");
            String escaped = target.toString().replace("'", "''");
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escaped + "'");
            }
            log.info("Pre-migration safety backup written to {}", target);
        } catch (IOException | SQLException e) {
            // A failed safety copy must not strand the shop unable to open the application
            // at all - log it (NFR-10) loudly and proceed with the migration anyway, the
            // same "never block on a backup failure" stance BackupService takes elsewhere.
            log.error("Could not take pre-migration safety backup - proceeding with migration anyway", e);
        }
    }
}
