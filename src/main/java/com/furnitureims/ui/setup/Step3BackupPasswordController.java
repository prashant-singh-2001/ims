package com.furnitureims.ui.setup;

import com.furnitureims.service.SettingsService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import org.springframework.stereotype.Component;

/**
 * Setup wizard step 3: the backup encryption password (FR-BAK-05, FR-BAK-06). The
 * plain-text password is never persisted - {@link SettingsService#setBackupPassword}
 * stores a bcrypt verifier (so a later re-entry can be confirmed correct) and a
 * Windows-DPAPI-protected copy (so the nightly scheduled backup in milestone M8 can run
 * unattended); see that method's Javadoc for why the second one doesn't violate FR-BAK-06.
 */
@Component
public class Step3BackupPasswordController implements WizardStep {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final SettingsService settingsService;

    @FXML private PasswordField backupPasswordField;
    @FXML private PasswordField confirmBackupPasswordField;
    @FXML private CheckBox warningAcknowledgedCheck;

    public Step3BackupPasswordController(SettingsService settingsService) {
        this.settingsService = settingsService;
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
        settingsService.setBackupPassword(backupPasswordField.getText());
    }
}
