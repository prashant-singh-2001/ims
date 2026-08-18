package com.furnitureims.ui.login;

import com.furnitureims.domain.AppUser;
import com.furnitureims.service.AppSession;
import com.furnitureims.service.AuthService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

/**
 * One screen serving two purposes (docs/03-screens.md section 2.2): the full-screen login
 * shown once at startup, and the same screen shown as a lock overlay after an idle
 * timeout or manual lock. Which one this was determines what happens after a successful
 * unlock - see {@link #onAuthenticated()}.
 */
@Component
public class LoginController {

    private static final int MIN_NEW_PASSWORD_LENGTH = 8;

    private final AuthService authService;
    private final SceneRouter sceneRouter;
    private final IdleLockManager idleLockManager;
    private final AppSession appSession;

    @FXML private VBox loginPane;
    @FXML private VBox recoveryPane;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button unlockButton;
    @FXML private Label errorLabel;
    @FXML private Label throttleLabel;

    @FXML private TextField recoveryUsernameField;
    @FXML private TextField recoveryCodeField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmNewPasswordField;
    @FXML private Label recoveryErrorLabel;
    @FXML private Label newRecoveryCodeLabel;

    private Timeline throttleTicker;

    public LoginController(AuthService authService, SceneRouter sceneRouter, IdleLockManager idleLockManager,
                            AppSession appSession) {
        this.authService = authService;
        this.sceneRouter = sceneRouter;
        this.idleLockManager = idleLockManager;
        this.appSession = appSession;
    }

    @FXML
    private void initialize() {
        showLoginPane();
    }

    @FXML
    private void onUnlockClicked() {
        String username = text(usernameField);
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        try {
            AppUser user = authService.login(username, password);
            appSession.setCurrentUser(user);
            errorLabel.setText("");
            throttleLabel.setText("");
            passwordField.clear();
            onAuthenticated();
        } catch (AuthService.AuthException e) {
            errorLabel.setText(e.getMessage());
            passwordField.clear();
            if (authService.throttleSecondsRemaining() > 0) {
                startThrottleCountdown();
            }
        }
    }

    private void onAuthenticated() {
        if (sceneRouter.isLockOverlayVisible()) {
            sceneRouter.hideLockOverlay();
            idleLockManager.notifyUnlocked();
        } else {
            sceneRouter.navigate(Route.DASHBOARD);
            idleLockManager.start();
        }
    }

    private void startThrottleCountdown() {
        if (throttleTicker != null) {
            throttleTicker.stop();
        }
        unlockButton.setDisable(true);
        throttleTicker = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long remaining = authService.throttleSecondsRemaining();
            if (remaining <= 0) {
                throttleLabel.setText("");
                unlockButton.setDisable(false);
                throttleTicker.stop();
            } else {
                throttleLabel.setText("Please wait " + remaining + "s before trying again");
            }
        }));
        throttleTicker.setCycleCount(Timeline.INDEFINITE);
        throttleTicker.play();
    }

    @FXML
    private void onForgotPasswordClicked() {
        recoveryErrorLabel.setText("");
        newRecoveryCodeLabel.setText("");
        loginPane.setVisible(false);
        loginPane.setManaged(false);
        recoveryPane.setVisible(true);
        recoveryPane.setManaged(true);
    }

    @FXML
    private void onCancelRecoveryClicked() {
        showLoginPane();
    }

    @FXML
    private void onResetPasswordClicked() {
        String username = text(recoveryUsernameField);
        String code = text(recoveryCodeField);
        String newPassword = newPasswordField.getText() == null ? "" : newPasswordField.getText();
        String confirm = confirmNewPasswordField.getText() == null ? "" : confirmNewPasswordField.getText();

        if (newPassword.length() < MIN_NEW_PASSWORD_LENGTH) {
            recoveryErrorLabel.setText("New password must be at least " + MIN_NEW_PASSWORD_LENGTH + " characters.");
            return;
        }
        if (!newPassword.equals(confirm)) {
            recoveryErrorLabel.setText("New passwords do not match.");
            return;
        }

        try {
            String newRecoveryCode = authService.resetPasswordWithRecoveryCode(username, code, newPassword);
            recoveryErrorLabel.setText("");
            newRecoveryCodeLabel.setText(
                    "Password reset. Your new recovery code is:\n" + newRecoveryCode
                            + "\n\nWrite this down now - the old code no longer works.");
            recoveryCodeField.clear();
            newPasswordField.clear();
            confirmNewPasswordField.clear();
        } catch (AuthService.AuthException e) {
            recoveryErrorLabel.setText(e.getMessage());
        }
    }

    private void showLoginPane() {
        recoveryPane.setVisible(false);
        recoveryPane.setManaged(false);
        loginPane.setVisible(true);
        loginPane.setManaged(true);
        errorLabel.setText("");
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
