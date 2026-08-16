package com.furnitureims.ui.setup;

import com.furnitureims.service.SetupService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

/**
 * Setup wizard step 2: the owner's login and the one-time recovery code (FR-AUTH-01,
 * FR-AUTH-08). Account creation is a deliberate in-step action (the "Create Account"
 * button), not something that happens as a side effect of {@link #validate()} - validate()
 * only checks whether it is safe to move on, which for this step means "has the account
 * been created, and has the owner confirmed they saved the recovery code".
 */
@Component
public class Step2OwnerLoginController implements WizardStep {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final SetupService setupService;

    @FXML private VBox credentialsPane;
    @FXML private VBox recoveryCodePane;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button createAccountButton;
    @FXML private Label credentialsErrorLabel;
    @FXML private Label recoveryCodeLabel;
    @FXML private CheckBox recoveryCodeConfirmedCheck;

    private boolean accountCreated = false;

    public Step2OwnerLoginController(SetupService setupService) {
        this.setupService = setupService;
    }

    @FXML
    private void initialize() {
        recoveryCodePane.setVisible(false);
        recoveryCodePane.setManaged(false);
        credentialsErrorLabel.setText("");
    }

    @FXML
    private void onCreateAccountClicked() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String confirm = confirmPasswordField.getText() == null ? "" : confirmPasswordField.getText();

        if (username.isBlank()) {
            credentialsErrorLabel.setText("Choose a username.");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            credentialsErrorLabel.setText("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            return;
        }
        if (!password.equals(confirm)) {
            credentialsErrorLabel.setText("Passwords do not match.");
            return;
        }

        try {
            String recoveryCode = setupService.createOwnerAccount(username, password);
            recoveryCodeLabel.setText(recoveryCode);
            accountCreated = true;
            credentialsErrorLabel.setText("");
            credentialsPane.setVisible(false);
            credentialsPane.setManaged(false);
            recoveryCodePane.setVisible(true);
            recoveryCodePane.setManaged(true);
        } catch (IllegalStateException e) {
            credentialsErrorLabel.setText(e.getMessage());
        }
    }

    @Override
    public String validate() {
        if (!accountCreated) {
            return "Click \"Create Account\" to continue.";
        }
        if (!recoveryCodeConfirmedCheck.isSelected()) {
            return "Please confirm you have written down the recovery code before continuing.";
        }
        return null;
    }
}
