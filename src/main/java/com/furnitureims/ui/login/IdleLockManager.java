package com.furnitureims.ui.login;

import com.furnitureims.service.SettingsService;
import com.furnitureims.ui.SceneRouter;
import javafx.animation.PauseTransition;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

/**
 * Watches for input activity on the scene and locks the screen after the configured idle
 * period (FR-AUTH-04), plus exposes {@link #lockNow()} for the manual lock command
 * (FR-AUTH-05). Started once, after the first successful login of a session.
 */
@Component
public class IdleLockManager {

    private final SceneRouter sceneRouter;
    private final SettingsService settingsService;

    private PauseTransition idleTimer;
    private boolean started = false;

    public IdleLockManager(SceneRouter sceneRouter, SettingsService settingsService) {
        this.sceneRouter = sceneRouter;
        this.settingsService = settingsService;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;

        int minutes = settingsService.idleLockMinutes();
        if (minutes <= 0) {
            return; // 0 means "never" (FR-AUTH-04)
        }

        idleTimer = new PauseTransition(Duration.minutes(minutes));
        idleTimer.setOnFinished(e -> lockNow());

        Scene scene = sceneRouter.scene();
        scene.addEventFilter(MouseEvent.ANY, this::onActivity);
        scene.addEventFilter(KeyEvent.ANY, this::onActivity);

        idleTimer.playFromStart();
    }

    private void onActivity(Event event) {
        if (idleTimer != null && !sceneRouter.isLockOverlayVisible()) {
            idleTimer.playFromStart();
        }
    }

    public void lockNow() {
        sceneRouter.showLockOverlay();
        if (idleTimer != null) {
            idleTimer.stop();
        }
    }

    /** Called once the owner successfully unlocks, so the idle clock starts fresh. */
    public void notifyUnlocked() {
        if (idleTimer != null) {
            idleTimer.playFromStart();
        }
    }
}
