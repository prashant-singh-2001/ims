package com.furnitureims.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * The database is a single SQLite file under {@link AppPaths#databaseFile()} - see
 * docs/02-data-model.md section 6 for why: it is what makes a backup archive as simple as
 * "copy this one file", and it matches a single-user desktop app with no server.
 * <p>
 * Deliberately not a pooled DataSource (Spring Boot's default is HikariCP). SQLite allows
 * only one writer at a time regardless of how many pooled connections exist, and a single-user
 * desktop app has no concurrent-request load to pool for - the driver's own non-pooling
 * DataSource is the simpler, more honest fit.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(AppPaths appPaths) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        String url = "jdbc:sqlite:" + appPaths.databaseFile();
        dataSource.setUrl(url);

        log.info("Using database at {}", appPaths.databaseFile());
        return dataSource;
    }
}
