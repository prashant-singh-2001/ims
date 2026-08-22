package com.piecetrack.ui.setup;

import com.piecetrack.domain.LicenseState;
import com.piecetrack.service.LicenseService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import org.springframework.stereotype.Component;

/**
 * Setup wizard step 5 (M14, FR-LIC-01): binds this installation to the activation key the
 * shop was given. Unlike {@link Step4GoogleDriveController}, this step is NOT skippable -
 * {@link #validate()} blocks "Finish" until {@link LicenseService#activate} has actually
 * succeeded, which is the whole point: an unauthorised copy is stopped here, before it ever
 * reaches the login screen, rather than merely detected afterwards.
 * <p>
 * Runs the network call off the JavaFX Application Thread via a {@link Task}, the same
 * pattern {@code Step4GoogleDriveController.onConnectGoogleDriveClicked} uses for the OAuth
 * connect flow - activation is the one moment in this milestone that genuinely needs
 * connectivity; every other check in M14 runs against whatever lease is already stored.
 */
@Component
public class Step5ActivationController implements WizardStep {

    private final LicenseService licenseService;

    @FXML private TextField activationKeyField;
    @FXML private Button activateButton;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label statusLabel;
    @FXML private Label errorLabel;

    private boolean activated;

    public Step5ActivationController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @FXML
    private void initialize() {
        errorLabel.setText("");
        progressIndicator.setVisible(false);
        // Re-entering this step (Back then Next again) should not demand the key twice.
        activated = licenseService.state() != LicenseState.UNLICENSED;
        statusLabel.setText(activated ? "Activated." : "");
    }

    @FXML
    private void onActivateClicked() {
        String key = activationKeyField.getText();
        if (key == null || key.isBlank()) {
            errorLabel.setText("Enter the activation key you were given.");
            return;
        }
        errorLabel.setText("");
        setBusy(true, "Activating...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                licenseService.activate(key);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            activated = true;
            setBusy(false, "Activated.");
        });
        task.setOnFailed(e -> {
            setBusy(false, "");
            Throwable ex = task.getException();
            errorLabel.setText(ex == null || ex.getMessage() == null ? "An unexpected error occurred." : ex.getMessage());
        });
        Thread thread = new Thread(task, "wizard-license-activate");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        progressIndicator.setVisible(busy);
        activateButton.setDisable(busy);
        statusLabel.setText(message);
    }

    @Override
    public String validate() {
        return activated ? null : "Enter your activation key and click Activate before finishing setup.";
    }
}
