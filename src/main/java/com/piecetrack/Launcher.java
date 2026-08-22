package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.ui.GlobalErrorHandler;
import javafx.application.Application;

import java.nio.file.Path;

/**
 * Real process entry point. {@link PieceTrackFxApp} is not launched directly as the
 * jar's main class because a class that both extends {@code javafx.application.Application}
 * and is the JAR's Main-Class can confuse module-path detection during packaging
 * (jlink/jpackage, milestone M9) - splitting them out is the standard, low-risk workaround.
 * <p>
 * This class has one more job: Logback reads its config and opens the log file before the
 * Spring context exists, so the log directory can't come from the {@link AppPaths} Spring
 * bean - it has to be a system property, set here, before anything else runs or logs a
 * single line.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // M15: must run before anything else, including the resolveRoot() call two lines
        // down - see AppPaths.migrateLegacyRootIfNeeded's own Javadoc for why.
        AppPaths.migrateLegacyRootIfNeeded();

        Path logDir = AppPaths.resolveRoot().resolve("logs");
        System.setProperty("PIECETRACK_LOG_DIR", logDir.toString());

        // NFR-11: installed before any other thread (Spring's context refresh, its
        // @Scheduled pool, JavaFX's own worker threads) gets a chance to start.
        GlobalErrorHandler.installDefault();

        Application.launch(PieceTrackFxApp.class, args);
    }
}
