package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.ui.GlobalErrorHandler;
import javafx.application.Application;

import java.nio.file.Path;

/**
 * Real process entry point. {@link FurnitureImsFxApp} is not launched directly as the
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
        Path logDir = AppPaths.resolveRoot().resolve("logs");
        System.setProperty("FURNITURE_IMS_LOG_DIR", logDir.toString());

        // NFR-11: installed before any other thread (Spring's context refresh, its
        // @Scheduled pool, JavaFX's own worker threads) gets a chance to start.
        GlobalErrorHandler.installDefault();

        Application.launch(FurnitureImsFxApp.class, args);
    }
}
