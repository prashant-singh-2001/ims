package com.furnitureims.ui.setup;

import com.furnitureims.service.GoogleDriveService;
import com.furnitureims.service.SettingsService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import org.springframework.stereotype.Component;

/**
 * Setup wizard step 4: connect Google Drive for backups (FR-BAK-07, FR-BAK-08). Per
 * docs/03-screens.md section 2.1, this step is skippable - {@link #validate()} never
 * blocks finishing setup, and there is no {@link #commit()} override: like
 * {@code Step2OwnerLoginController}'s "Create Account" button, this step persists as part
 * of its own in-step "Connect Google Drive" action rather than on "Next", so typing
 * credentials in without clicking Connect does not silently save them (matching
 * {@code BackupSettingsController}'s separate Save/Connect actions on the same screen
 * reached later from Settings).
 * <p>
 * Was a permanently-disabled button until M11: the backup pipeline it depends on didn't
 * exist yet when this step was first built, that gap closed in M8, but this screen was
 * never rewired to the real flow that {@link GoogleDriveService#connect()} and
 * {@code BackupSettingsController} already used. Owners who skipped this step were finding
 * the real, working version anyway on the Settings > Backup screen - this just stops the
 * wizard from telling them it doesn't exist.
 */
@Component
public class Step4GoogleDriveController implements WizardStep {

    private final GoogleDriveService googleDriveService;
    private final SettingsService settingsService;

    @FXML private TextField googleClientIdField;
    @FXML private PasswordField googleClientSecretField;
    @FXML private Button connectButton;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label statusLabel;
    @FXML private Label errorLabel;

    public Step4GoogleDriveController(GoogleDriveService googleDriveService, SettingsService settingsService) {
        this.googleDriveService = googleDriveService;
        this.settingsService = settingsService;
    }

    @FXML
    private void initialize() {
        googleClientIdField.setText(settingsService.googleClientId().orElse(""));
        googleClientSecretField.setText(settingsService.googleClientSecret().orElse(""));
        errorLabel.setText("");
        progressIndicator.setVisible(false);
        reload();
    }

    private void reload() {
        statusLabel.setText(googleDriveService.isConnected() ? "Connected." : "Not connected yet.");
    }

    @FXML
    private void onConnectGoogleDriveClicked() {
        String clientId = nullIfBlank(googleClientIdField.getText());
        String clientSecret = nullIfBlank(googleClientSecretField.getText());
        if (clientId == null || clientSecret == null) {
            errorLabel.setText("Enter both the Google client ID and client secret first.");
            return;
        }
        settingsService.setGoogleOAuthClient(clientId, clientSecret);
        errorLabel.setText("");
        setBusy(true, "Opening your browser for Google sign-in...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                googleDriveService.connect();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, "");
            reload();
        });
        task.setOnFailed(e -> {
            setBusy(false, "");
            Throwable ex = task.getException();
            errorLabel.setText(ex == null || ex.getMessage() == null ? "An unexpected error occurred." : ex.getMessage());
        });
        Thread thread = new Thread(task, "wizard-google-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        progressIndicator.setVisible(busy);
        connectButton.setDisable(busy);
        statusLabel.setText(message);
    }

    @Override
    public String validate() {
        return null;
    }

    private static String nullIfBlank(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}
