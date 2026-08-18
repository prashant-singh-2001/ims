package com.furnitureims.ui.purchase;

import com.furnitureims.domain.IndianState;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.service.SettingsService;
import com.furnitureims.service.SupplierService;
import com.furnitureims.ui.HasScreenTitle;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.util.GstinValidator;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Supplier editor (FR-PUR-01; docs/03-screens.md 5.1). GSTIN format/state-prefix
 * validation (FR-PUR-02) is a non-blocking warning here, unlike the shop's own GSTIN in
 * the setup wizard - a mismatch is shown but never prevents saving.
 */
@Component
public class SupplierEditorController implements HasScreenTitle {

    private final SupplierService supplierService;
    private final SettingsService settingsService;
    private final SceneRouter sceneRouter;

    /** M10: see ItemModelEditorController.screenTitle for the pattern this follows. */
    private final StringProperty screenTitle = new SimpleStringProperty("");

    @FXML private TextField nameField;
    @FXML private Label gstinLabel;
    @FXML private VBox gstinBox;
    @FXML private TextField gstinField;
    @FXML private Label gstinWarningLabel;
    @FXML private TextField addressLine1Field;
    @FXML private TextField addressLine2Field;
    @FXML private TextField cityField;
    @FXML private TextField pincodeField;
    @FXML private Label stateLabel;
    @FXML private ComboBox<IndianState> stateCombo;
    @FXML private TextField phoneField;
    @FXML private TextField emailField;
    @FXML private TextField contactPersonField;
    @FXML private TextField openingBalanceField;
    @FXML private CheckBox activeCheck;
    @FXML private TextArea notesArea;
    @FXML private Label errorLabel;

    private Long editingId;
    private boolean pendingIsNew = true;

    public SupplierEditorController(SupplierService supplierService, SettingsService settingsService,
                                     SceneRouter sceneRouter) {
        this.supplierService = supplierService;
        this.settingsService = settingsService;
        this.sceneRouter = sceneRouter;
    }

    public void openForNew() {
        editingId = null;
        pendingIsNew = true;
    }

    public void openForEdit(long supplierId) {
        editingId = supplierId;
        pendingIsNew = false;
    }

    @Override
    public ReadOnlyStringProperty screenTitleProperty() {
        return screenTitle;
    }

    @FXML
    private void initialize() {
        stateCombo.setItems(FXCollections.observableArrayList(IndianState.values()));
        errorLabel.setText("");
        gstinWarningLabel.setText("");
        gstinField.textProperty().addListener((obs, was, isNow) -> updateGstinWarning());
        stateCombo.valueProperty().addListener((obs, was, isNow) -> updateGstinWarning());
        applyGstVisibility();

        if (pendingIsNew) {
            resetForNew();
        } else {
            loadForEdit(editingId);
        }
    }

    /** M10: GSTIN is GST-only and hides entirely. State stays visible either way - it is
     *  ordinary address data that also happens to decide CGST/SGST vs IGST when GST is on -
     *  only its required-ness and copy change (see SupplierService.applyStateSentinelIfNeeded
     *  for why it is no longer a hard requirement once GST is off). */
    private void applyGstVisibility() {
        boolean gstEnabled = settingsService.isGstEnabled();
        gstinLabel.setVisible(gstEnabled);
        gstinLabel.setManaged(gstEnabled);
        gstinBox.setVisible(gstEnabled);
        gstinBox.setManaged(gstEnabled);
        stateLabel.setText(gstEnabled ? "State *" : "State");
        stateCombo.setPromptText(gstEnabled ? "Decides CGST/SGST vs IGST on purchases" : "Optional");
    }

    private void updateGstinWarning() {
        String gstin = gstinField.getText();
        IndianState state = stateCombo.getValue();
        if (gstin == null || gstin.isBlank() || state == null) {
            gstinWarningLabel.setText("");
            return;
        }
        String trimmed = gstin.trim().toUpperCase();
        if (!GstinValidator.isValidFormat(trimmed)) {
            gstinWarningLabel.setText("This does not look like a valid GSTIN, but you can still save it.");
        } else if (!GstinValidator.stateCodeMatches(trimmed, state.gstCode())) {
            gstinWarningLabel.setText("This GSTIN's state code doesn't match " + state.displayName()
                    + " - double-check it, but you can still save it.");
        } else {
            gstinWarningLabel.setText("");
        }
    }

    private void resetForNew() {
        screenTitle.set("New Supplier");
        nameField.clear();
        gstinField.clear();
        addressLine1Field.clear();
        addressLine2Field.clear();
        cityField.clear();
        pincodeField.clear();
        stateCombo.setValue(null);
        phoneField.clear();
        emailField.clear();
        contactPersonField.clear();
        openingBalanceField.clear();
        activeCheck.setSelected(true);
        notesArea.clear();
    }

    private void loadForEdit(long id) {
        Supplier s = supplierService.findById(id)
                .orElseThrow(() -> new IllegalStateException("Supplier not found: " + id));
        screenTitle.set("Edit Supplier - " + s.name());
        nameField.setText(s.name());
        gstinField.setText(s.gstin());
        addressLine1Field.setText(s.addressLine1());
        addressLine2Field.setText(s.addressLine2());
        cityField.setText(s.city());
        pincodeField.setText(s.pincode());
        if (s.stateCode() != null) {
            stateCombo.setValue(IndianState.byGstCode(s.stateCode()));
        }
        phoneField.setText(s.phone());
        emailField.setText(s.email());
        contactPersonField.setText(s.contactPerson());
        openingBalanceField.setText(s.openingBalance().rupees().toPlainString());
        activeCheck.setSelected(s.active());
        notesArea.setText(s.notes());
    }

    @FXML
    private void onSaveClicked() {
        try {
            IndianState state = stateCombo.getValue();
            // M10: state is only a hard requirement while GST is on - it's what decides
            // CGST/SGST vs IGST. Left blank with GST off, SupplierService.
            // applyStateSentinelIfNeeded fills in the shop's own state instead of this
            // method rejecting the save itself.
            if (settingsService.isGstEnabled() && state == null) {
                throw new IllegalArgumentException("Please select the supplier's state.");
            }
            Money openingBalance = Money.ZERO;
            String balanceText = openingBalanceField.getText();
            if (balanceText != null && !balanceText.isBlank()) {
                try {
                    openingBalance = Money.ofRupees(new BigDecimal(balanceText.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Opening balance must be a number.");
                }
            }

            Supplier supplier = new Supplier(editingId == null ? 0 : editingId,
                    requireText(nameField.getText(), "Supplier name"), nullIfBlank(gstinField.getText()),
                    nullIfBlank(addressLine1Field.getText()), nullIfBlank(addressLine2Field.getText()),
                    nullIfBlank(cityField.getText()), nullIfBlank(pincodeField.getText()),
                    state == null ? null : state.displayName(), state == null ? null : state.gstCode(),
                    nullIfBlank(phoneField.getText()),
                    nullIfBlank(emailField.getText()), nullIfBlank(contactPersonField.getText()),
                    openingBalance, activeCheck.isSelected(), nullIfBlank(notesArea.getText()));

            if (editingId == null) {
                supplierService.create(supplier);
            } else {
                supplierService.update(supplier);
            }
            sceneRouter.navigate(Route.SUPPLIER_LIST);
        } catch (IllegalArgumentException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private static String requireText(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return text.trim();
    }

    private static String nullIfBlank(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}
