package com.furnitureims.ui.setup;

import com.furnitureims.service.SetupService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import org.springframework.stereotype.Component;

/**
 * Setup wizard step 3: the backup encryption password (FR-BAK-05, FR-BAK-06). This
 * password is never persisted - only a bcrypt verifier is, via
 * {@link SetupService#recordBackupPasswordVerifier}, so a later screen can confirm a
 * re-entered password is correct. Actual key derivation belongs to the backup pipeline
 * (milestone M8).
 */
@Component
public class Step3BackupPasswordController implements WizardStep {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final SetupService setupService;

    @FXML private PasswordField backupPasswordField;
    @FXML private PasswordField confirmBackupPasswordField;
    @FXML private CheckBox warningAcknowledgedCheck;

    public Step3BackupPasswordController(SetupService setupService) {
        this.setupService = setupService;
    }

    @Override
    public String validate() {
        String password = backupPasswordField.getText() == null ? "" : backupPasswordField.getText();
        String confirm = confirmBackupPasswordField.getText() == null ? "" : confirmBackupPasswordField.getText();

        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "Backup password must be at least " + MIN_PASSWORD_LENGTH + " characters.";
        }
        if (!password.equals(confirm)) {
            return "Backup passwords do not match.";
        }
        if (!warningAcknowledgedCheck.isSelected()) {
            return "Please confirm you understand a lost backup password cannot be recovered.";
        }
        return null;
    }

    @Override
    public void commit() {
        setupService.recordBackupPasswordVerifier(backupPasswordField.getText());
    }
}
