package com.furnitureims.ui.setup;

import com.furnitureims.domain.IndianState;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.service.SettingsService;
import com.furnitureims.service.SetupService;
import com.furnitureims.util.GstinValidator;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.springframework.stereotype.Component;

/** Setup wizard step 1: shop profile (FR-AUTH-01, FR-SYS-01) and the M10 GST toggle - the
 *  toggle itself is stored via {@link SettingsService}, not {@link ShopProfile}, but this is
 *  the one place every installation passes through before the app is usable at all, so it is
 *  where the owner is first asked. Changeable later from Settings. */
@Component
public class Step1ShopProfileController implements WizardStep {

    private final SetupService setupService;
    private final SettingsService settingsService;

    public Step1ShopProfileController(SetupService setupService, SettingsService settingsService) {
        this.setupService = setupService;
        this.settingsService = settingsService;
    }

    @FXML private TextField shopNameField;
    @FXML private TextField addressLine1Field;
    @FXML private TextField addressLine2Field;
    @FXML private TextField cityField;
    @FXML private TextField pincodeField;
    @FXML private ComboBox<IndianState> stateCombo;
    @FXML private CheckBox gstRegisteredCheck;
    @FXML private Label gstinLabel;
    @FXML private TextField gstinField;
    @FXML private Label registrationTypeLabel;
    @FXML private HBox registrationTypeBox;
    @FXML private RadioButton regularRadio;
    @FXML private RadioButton compositionRadio;
    @FXML private TextField phoneField;
    @FXML private TextField emailField;

    @FXML
    private void initialize() {
        stateCombo.getItems().setAll(IndianState.values());
        gstRegisteredCheck.selectedProperty().addListener((obs, was, isNow) -> applyGstFieldVisibility(isNow));
        applyGstFieldVisibility(gstRegisteredCheck.isSelected());
    }

    private void applyGstFieldVisibility(boolean gstRegistered) {
        gstinLabel.setVisible(gstRegistered);
        gstinLabel.setManaged(gstRegistered);
        gstinField.setVisible(gstRegistered);
        gstinField.setManaged(gstRegistered);
        registrationTypeLabel.setVisible(gstRegistered);
        registrationTypeLabel.setManaged(gstRegistered);
        registrationTypeBox.setVisible(gstRegistered);
        registrationTypeBox.setManaged(gstRegistered);
    }

    @Override
    public String validate() {
        if (isBlank(shopNameField.getText())) {
            return "Shop name is required.";
        }
        if (stateCombo.getValue() == null) {
            return "Please select the shop's state.";
        }
        if (gstRegisteredCheck.isSelected()) {
            if (isBlank(gstinField.getText())) {
                return "GSTIN is required.";
            }
            String gstin = gstinField.getText().trim().toUpperCase();
            if (!GstinValidator.isValidFormat(gstin)) {
                return "GSTIN does not look valid. Expected format: 22AAAAA0000A1Z5.";
            }
            if (!GstinValidator.stateCodeMatches(gstin, stateCombo.getValue().gstCode())) {
                return "This GSTIN's state code (" + gstin.substring(0, 2) + ") does not match "
                        + stateCombo.getValue().displayName() + " (" + stateCombo.getValue().gstCode() + "). "
                        + "Double-check the GSTIN or the selected state.";
            }
        }
        if (isBlank(phoneField.getText())) {
            return "A contact phone number is required.";
        }
        return null;
    }

    @Override
    public void commit() {
        setupService.saveShopProfile(toShopProfile());
        settingsService.setGstEnabled(gstRegisteredCheck.isSelected());
    }

    public ShopProfile toShopProfile() {
        IndianState state = stateCombo.getValue();
        String gstin = gstRegisteredCheck.isSelected() ? gstinField.getText().trim().toUpperCase() : null;
        return new ShopProfile(
                shopNameField.getText().trim(),
                nullIfBlank(addressLine1Field.getText()),
                nullIfBlank(addressLine2Field.getText()),
                nullIfBlank(cityField.getText()),
                nullIfBlank(pincodeField.getText()),
                state.displayName(),
                state.gstCode(),
                gstin,
                compositionRadio.isSelected()
                        ? ShopProfile.RegistrationType.COMPOSITION
                        : ShopProfile.RegistrationType.REGULAR,
                phoneField.getText().trim(),
                nullIfBlank(emailField.getText()),
                null,
                null,
                null
        );
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullIfBlank(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
