package com.furnitureims.ui.setup;

import com.furnitureims.service.CloudBackupProvider;
import com.furnitureims.service.CloudProviders;
import com.furnitureims.service.SettingsService;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Setup wizard step 4: connect a cloud backup destination (FR-BAK-07, FR-BAK-08). Per
 * docs/03-screens.md section 2.1, this step is skippable - {@link #validate()} never
 * blocks finishing setup, and there is no {@link #commit()} override: like
 * {@code Step2OwnerLoginController}'s "Create Account" button, this step persists as part
 * of its own in-step "Connect" action rather than on "Next", so typing credentials in
 * without clicking Connect does not silently save them (matching
 * {@code BackupSettingsController}'s separate Save/Connect actions on the same screen
 * reached later from Settings).
 * <p>
 * M12: the destination picker mirrors {@code BackupSettingsController}'s - OneDrive ships
 * with a built-in Azure app registration (see {@code OneDriveService}), so selecting it
 * hides the Google-only client ID/secret fields entirely, which is the whole point of
 * OneDrive not needing a per-shop setup step the way Google does.
 */
@Component
public class Step4GoogleDriveController implements WizardStep {

    private final CloudProviders cloudProviders;
    private final SettingsService settingsService;

    @FXML private ComboBox<CloudBackupProvider> destinationCombo;
    @FXML private Label googleClientIdLabel;
    @FXML private TextField googleClientIdField;
    @FXML private Label googleClientSecretLabel;
    @FXML private PasswordField googleClientSecretField;
    @FXML private Label googleCredentialHintLabel;
    @FXML private Button connectButton;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label statusLabel;
    @FXML private Label errorLabel;

    public Step4GoogleDriveController(CloudProviders cloudProviders, SettingsService settingsService) {
        this.cloudProviders = cloudProviders;
        this.settingsService = settingsService;
    }

    @FXML
    private void initialize() {
        googleClientIdField.setText(settingsService.googleClientId().orElse(""));
        googleClientSecretField.setText(settingsService.googleClientSecret().orElse(""));
        errorLabel.setText("");
        progressIndicator.setVisible(false);

        destinationCombo.setItems(FXCollections.observableArrayList(cloudProviders.all()));
        destinationCombo.setConverter(providerConverter());
        destinationCombo.setValue(cloudProviders.active());
        destinationCombo.valueProperty().addListener((obs, old, selected) -> onDestinationChanged(selected));
        onDestinationChanged(destinationCombo.getValue());
    }

    private void onDestinationChanged(CloudBackupProvider selected) {
        if (selected == null) {
            return;
        }
        settingsService.setBackupProvider(selected.id());
        boolean isGoogle = "GOOGLE_DRIVE".equals(selected.id());
        for (Node node : List.of(googleClientIdLabel, googleClientIdField, googleClientSecretLabel,
                googleClientSecretField, googleCredentialHintLabel)) {
            node.setVisible(isGoogle);
            node.setManaged(isGoogle);
        }
        connectButton.setText("Connect " + selected.displayName());
        reload();
    }

    private void reload() {
        CloudBackupProvider selected = destinationCombo.getValue();
        statusLabel.setText(selected != null && selected.isConnected() ? "Connected." : "Not connected yet.");
    }

    @FXML
    private void onConnectGoogleDriveClicked() {
        CloudBackupProvider selected = destinationCombo.getValue();
        if ("GOOGLE_DRIVE".equals(selected.id())) {
            String clientId = nullIfBlank(googleClientIdField.getText());
            String clientSecret = nullIfBlank(googleClientSecretField.getText());
            if (clientId == null || clientSecret == null) {
                errorLabel.setText("Enter both the Google client ID and client secret first.");
                return;
            }
            settingsService.setGoogleOAuthClient(clientId, clientSecret);
        }
        errorLabel.setText("");
        setBusy(true, "Opening your browser for " + selected.displayName() + " sign-in...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                selected.connect();
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
        Thread thread = new Thread(task, "wizard-cloud-connect");
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

    private static StringConverter<CloudBackupProvider> providerConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(CloudBackupProvider provider) {
                return provider == null ? "" : provider.displayName();
            }

            @Override
            public CloudBackupProvider fromString(String string) {
                throw new UnsupportedOperationException("Not editable");
            }
        };
    }
}
