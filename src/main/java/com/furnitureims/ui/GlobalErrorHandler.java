package com.furnitureims.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NFR-11: no raw exception or stack trace shall ever reach the owner's screen. Every
 * exception that escapes a JavaFX event handler, or any other thread in this process,
 * without being caught closer to where it happened ends up here - logged in full (NFR-10)
 * and shown to the owner as one plain-language sentence, never the exception's own message
 * or class name (which is exactly the kind of detail this exists to keep off the screen -
 * every other catch block in the UI layer already produces its own human-readable message
 * by design, so this is only ever reached by something nobody anticipated).
 */
public final class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private GlobalErrorHandler() {
    }

    /** Installs a JVM-wide default handler - the safety net for background threads (Spring
     *  {@code @Scheduled} pool, JavaFX's own worker threads) that never bind their own.
     *  Call once, as early in the process as possible, before any thread that matters
     *  starts doing work. */
    public static void installDefault() {
        Thread.setDefaultUncaughtExceptionHandler(GlobalErrorHandler::handle);
    }

    /** Also binds the handler directly to the calling thread. The JavaFX Application
     *  Thread already falls back to the default handler installed above, but binding here
     *  too removes any dependency on that fallback behaving the same way across JavaFX
     *  versions - this is the thread every screen's event handlers run on. */
    public static void installOnCurrentThread() {
        Thread.currentThread().setUncaughtExceptionHandler(GlobalErrorHandler::handle);
    }

    private static void handle(Thread thread, Throwable throwable) {
        log.error("Unhandled error on thread {}", thread.getName(), throwable);
        Runnable showDialog = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Something went wrong");
            alert.setHeaderText("This action could not be completed.");
            alert.setContentText("An unexpected error occurred. It has been recorded in the application "
                    + "log. Try again, and if this keeps happening, contact support with the date and time.");
            alert.getButtonTypes().setAll(ButtonType.OK);
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            showDialog.run();
        } else {
            try {
                Platform.runLater(showDialog);
            } catch (IllegalStateException e) {
                // JavaFX toolkit not started yet (a very early init() failure) - nothing more
                // can be shown to the owner; the log entry above is what NFR-10 requires.
            }
        }
    }
}
