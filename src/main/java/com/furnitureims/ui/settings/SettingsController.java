package com.furnitureims.ui.settings;

import com.furnitureims.config.AppPaths;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.EmailService;
import com.furnitureims.service.SettingsService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.util.GstinValidator;
import com.furnitureims.util.ImageResizer;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Settings screen (docs/03-screens.md section on Settings) - the Invoice/Email/WhatsApp
 * sections FR-DOC-01/03/04 require to be configurable. Shop profile's declaration,
 * signature and logo live on {@link ShopProfile} itself (set here, printed on every
 * document by {@code DocumentService}); SMTP and the message templates are generic
 * key/value settings via {@link SettingsService}.
 */
@Component
public class SettingsController {

    private final ShopProfileRepository shopProfileRepository;
    private final SettingsService settingsService;
    private final EmailService emailService;
    private final AppPaths appPaths;
    private final SceneRouter sceneRouter;

    @FXML private CheckBox gstEnabledCheck;
    @FXML private GridPane gstDetailsGrid;
    @FXML private Label shopStateLabel;
    @FXML private TextField gstinField;
    @FXML private RadioButton regularRadio;
    @FXML private RadioButton compositionRadio;
    @FXML private Label taxStatusLabel;

    @FXML private TextArea declarationArea;
    @FXML private TextField signatureField;
    @FXML private CheckBox showPieceTagsCheck;
    @FXML private Label logoStatusLabel;
    @FXML private Label invoiceStatusLabel;

    @FXML private TextField smtpHostField;
    @FXML private TextField smtpPortField;
    @FXML private CheckBox smtpUseTlsCheck;
    @FXML private TextField smtpUsernameField;
    @FXML private PasswordField smtpAppPasswordField;
    @FXML private TextField emailFromNameField;
    @FXML private TextField emailSubjectField;
    @FXML private TextArea emailBodyArea;
    @FXML private TextField testEmailToField;
    @FXML private Label emailStatusLabel;

    @FXML private TextArea whatsAppTemplateArea;
    @FXML private Label whatsAppStatusLabel;

    public SettingsController(ShopProfileRepository shopProfileRepository, SettingsService settingsService,
                               EmailService emailService, AppPaths appPaths, SceneRouter sceneRouter) {
        this.shopProfileRepository = shopProfileRepository;
        this.settingsService = settingsService;
        this.emailService = emailService;
        this.appPaths = appPaths;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        loadTaxSection();
        loadInvoiceSection();
        loadEmailSection();
        loadWhatsAppSection();
    }

    // ---- Tax / GST section (M10) -----------------------------------------------------------

    private void loadTaxSection() {
        boolean gstEnabled = settingsService.isGstEnabled();
        gstEnabledCheck.setSelected(gstEnabled);
        gstEnabledCheck.selectedProperty().addListener((obs, was, isNow) -> applyGstDetailsVisibility(isNow));

        ShopProfile shop = shopProfileRepository.find().orElse(null);
        shopStateLabel.setText(shop == null ? "-" : shop.stateName() + " (" + shop.stateCode() + ")");
        gstinField.setText(shop == null ? "" : nullToEmpty(shop.gstin()));
        if (shop != null && shop.registrationType() == ShopProfile.RegistrationType.COMPOSITION) {
            compositionRadio.setSelected(true);
        } else {
            regularRadio.setSelected(true);
        }

        applyGstDetailsVisibility(gstEnabled);
        taxStatusLabel.setText("");
    }

    /** State/GSTIN/registration type are only meaningful once GST is on - hidden as one
     *  block otherwise, unlike the setup wizard's per-field split (there, State is its own
     *  always-required address field being collected for the first time; here it is a
     *  read-only display next to the fields that exist only to support GST). */
    private void applyGstDetailsVisibility(boolean gstEnabled) {
        gstDetailsGrid.setVisible(gstEnabled);
        gstDetailsGrid.setManaged(gstEnabled);
    }

    /** M10: the only place GSTIN, and therefore registration type, can be entered outside
     *  the setup wizard - a shop that started with GST off never had a GSTIN collected at
     *  all, so turning the toggle on later needs an entry point for it right here. */
    @FXML
    private void onSaveTaxSettingsClicked() {
        boolean gstEnabled = gstEnabledCheck.isSelected();
        try {
            if (gstEnabled) {
                ShopProfile existing = shopProfileRepository.find()
                        .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
                if (gstinField.getText() == null || gstinField.getText().isBlank()) {
                    taxStatusLabel.setText("GSTIN is required to turn GST on.");
                    return;
                }
                String gstin = gstinField.getText().trim().toUpperCase();
                if (!GstinValidator.isValidFormat(gstin)) {
                    taxStatusLabel.setText("GSTIN does not look valid. Expected format: 22AAAAA0000A1Z5.");
                    return;
                }
                if (!GstinValidator.stateCodeMatches(gstin, existing.stateCode())) {
                    taxStatusLabel.setText("This GSTIN's state code (" + gstin.substring(0, 2)
                            + ") does not match the shop's state, " + existing.stateName()
                            + " (" + existing.stateCode() + ").");
                    return;
                }
                ShopProfile updated = new ShopProfile(existing.shopName(), existing.addressLine1(),
                        existing.addressLine2(), existing.city(), existing.pincode(), existing.stateName(),
                        existing.stateCode(), gstin,
                        compositionRadio.isSelected()
                                ? ShopProfile.RegistrationType.COMPOSITION
                                : ShopProfile.RegistrationType.REGULAR,
                        existing.phone(), existing.email(), existing.logoPath(), existing.invoiceDeclaration(),
                        existing.signatureText());
                shopProfileRepository.save(updated);
            }
            settingsService.setGstEnabled(gstEnabled);
            taxStatusLabel.setText("Saved.");
        } catch (IllegalStateException e) {
            taxStatusLabel.setText(e.getMessage());
        }
    }

    // ---- Invoice section (FR-DOC-01/06) --------------------------------------------------

    private void loadInvoiceSection() {
        ShopProfile shop = shopProfileRepository.find().orElse(null);
        declarationArea.setText(shop == null ? "" : nullToEmpty(shop.invoiceDeclaration()));
        signatureField.setText(shop == null ? "" : nullToEmpty(shop.signatureText()));
        logoStatusLabel.setText(shop != null && shop.logoPath() != null && !shop.logoPath().isBlank()
                ? "Logo on file: " + shop.logoPath() : "No logo set");
        showPieceTagsCheck.setSelected(settingsService.showPieceTagsUnderGroupedLine());
        invoiceStatusLabel.setText("");
    }

    @FXML
    private void onUploadLogoClicked() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Shop Logo");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(logoStatusLabel.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            ShopProfile existing = shopProfileRepository.find()
                    .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
            Files.createDirectories(appPaths.photos());
            String relativePath = "shop-logo" + extensionOf(file.getName());
            ImageResizer.resizeAndSave(file.toPath(), appPaths.photos().resolve(relativePath));

            shopProfileRepository.save(withLogoPath(existing, relativePath));
            logoStatusLabel.setText("Logo on file: " + relativePath);
            invoiceStatusLabel.setText("Logo updated.");
        } catch (IllegalStateException | UncheckedIOException | IOException e) {
            invoiceStatusLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onSaveInvoiceSettingsClicked() {
        try {
            ShopProfile existing = shopProfileRepository.find()
                    .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
            ShopProfile updated = new ShopProfile(existing.shopName(), existing.addressLine1(),
                    existing.addressLine2(), existing.city(), existing.pincode(), existing.stateName(),
                    existing.stateCode(), existing.gstin(), existing.registrationType(), existing.phone(),
                    existing.email(), existing.logoPath(), nullIfBlank(declarationArea.getText()),
                    nullIfBlank(signatureField.getText()));
            shopProfileRepository.save(updated);
            settingsService.setShowPieceTagsUnderGroupedLine(showPieceTagsCheck.isSelected());
            invoiceStatusLabel.setText("Saved.");
        } catch (IllegalStateException e) {
            invoiceStatusLabel.setText(e.getMessage());
        }
    }

    private static ShopProfile withLogoPath(ShopProfile existing, String logoPath) {
        return new ShopProfile(existing.shopName(), existing.addressLine1(), existing.addressLine2(),
                existing.city(), existing.pincode(), existing.stateName(), existing.stateCode(), existing.gstin(),
                existing.registrationType(), existing.phone(), existing.email(), logoPath,
                existing.invoiceDeclaration(), existing.signatureText());
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase() : ".png";
    }

    // ---- Email section (FR-DOC-04) --------------------------------------------------------

    private void loadEmailSection() {
        SettingsService.SmtpSettings smtp = settingsService.smtpSettings();
        smtpHostField.setText(smtp.host());
        smtpPortField.setText(Integer.toString(smtp.port()));
        smtpUseTlsCheck.setSelected(smtp.useTls());
        smtpUsernameField.setText(smtp.username());
        smtpAppPasswordField.setText(smtp.appPassword());
        emailFromNameField.setText(smtp.fromName());
        emailSubjectField.setText(settingsService.emailSubjectTemplate());
        emailBodyArea.setText(settingsService.emailBodyTemplate());
        emailStatusLabel.setText("");
    }

    @FXML
    private void onSaveEmailSettingsClicked() {
        try {
            int port = Integer.parseInt(smtpPortField.getText().trim());
            settingsService.setSmtpSettings(new SettingsService.SmtpSettings(
                    smtpHostField.getText(), port, smtpUseTlsCheck.isSelected(), smtpUsernameField.getText(),
                    smtpAppPasswordField.getText(), emailFromNameField.getText()));
            settingsService.setEmailSubjectTemplate(emailSubjectField.getText());
            settingsService.setEmailBodyTemplate(emailBodyArea.getText());
            emailStatusLabel.setText("Saved.");
        } catch (NumberFormatException e) {
            emailStatusLabel.setText("SMTP port must be a number.");
        }
    }

    @FXML
    private void onSendTestEmailClicked() {
        String to = testEmailToField.getText();
        if (to == null || to.isBlank()) {
            emailStatusLabel.setText("Enter an address to send the test email to.");
            return;
        }
        try {
            emailService.sendTestEmail(to.trim());
            emailStatusLabel.setText("Test email sent to " + to.trim() + ".");
        } catch (IllegalArgumentException | IllegalStateException e) {
            emailStatusLabel.setText(e.getMessage());
        }
    }

    // ---- WhatsApp section (FR-DOC-03) -----------------------------------------------------

    private void loadWhatsAppSection() {
        whatsAppTemplateArea.setText(settingsService.whatsAppMessageTemplate());
        whatsAppStatusLabel.setText("");
    }

    @FXML
    private void onSaveWhatsAppSettingsClicked() {
        settingsService.setWhatsAppMessageTemplate(whatsAppTemplateArea.getText());
        whatsAppStatusLabel.setText("Saved.");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @FXML
    private void onBackupSettingsClicked() {
        sceneRouter.navigate(Route.BACKUP_SETTINGS);
    }

    @FXML
    private void onAuditLogClicked() {
        sceneRouter.navigate(Route.AUDIT_LOG);
    }

}
