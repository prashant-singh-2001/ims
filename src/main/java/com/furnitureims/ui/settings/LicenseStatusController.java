package com.furnitureims.ui.settings;

import com.furnitureims.domain.License;
import com.furnitureims.domain.LicenseState;
import com.furnitureims.repository.LicenseRepository;
import com.furnitureims.service.LicenseService;
import com.furnitureims.util.MachineFingerprint;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Settings > Licence (M14, reached via {@code Route.LICENSE_STATUS}): what the owner (or
 * whoever is on the phone with the supplier troubleshooting an activation problem) needs to
 * see - the current state, this machine's fingerprint, and when the lease was last renewed.
 * "Check Now" runs {@link LicenseService#refreshLease()} on demand, off the JavaFX
 * Application Thread, the same {@link Task} pattern every other network-touching screen in
 * this app uses ({@code BackupSettingsController}, {@code Step4GoogleDriveController}).
 */
@Component
public class LicenseStatusController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final LicenseService licenseService;
    private final LicenseRepository licenseRepository;

    @FXML private Button checkNowButton;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label statusLabel;
    @FXML private Label statusExplanationLabel;
    @FXML private Label shopNameLabel;
    @FXML private Label fingerprintLabel;
    @FXML private Label activatedAtLabel;
    @FXML private Label leaseExpiresLabel;
    @FXML private Label lastContactLabel;
    @FXML private Label errorLabel;

    public LicenseStatusController(LicenseService licenseService, LicenseRepository licenseRepository) {
        this.licenseService = licenseService;
        this.licenseRepository = licenseRepository;
    }

    @FXML
    private void initialize() {
        errorLabel.setText("");
        progressIndicator.setVisible(false);
        fingerprintLabel.setText(MachineFingerprint.stableId());
        reload();
    }

    private void reload() {
        LicenseState state = licenseService.state();
        statusLabel.getStyleClass().removeAll("text-warning", "text-danger");
        statusLabel.setText(switch (state) {
            case ACTIVE -> "Active";
            case GRACE -> "Active (renewal needed soon)";
            case WIND_DOWN -> "Read-only";
            case UNLICENSED -> "Not activated";
        });
        if (state == LicenseState.GRACE) {
            statusLabel.getStyleClass().add("text-warning");
        } else if (state == LicenseState.WIND_DOWN || state == LicenseState.UNLICENSED) {
            statusLabel.getStyleClass().add("text-danger");
        }
        statusExplanationLabel.setText(switch (state) {
            case ACTIVE -> "Everything works normally.";
            case GRACE -> "Everything still works, but the lease expires soon - make sure this PC "
                    + "connects to the internet at some point so it can renew automatically.";
            case WIND_DOWN -> "New invoices, bills and payments cannot be created. Existing records, "
                    + "reports and backups are all still fully available. Contact the supplier.";
            case UNLICENSED -> "This installation was never activated.";
        });

        Optional<License> license = licenseRepository.find();
        shopNameLabel.setText(license.map(License::shopName).filter(name -> name != null).orElse("-"));
        activatedAtLabel.setText(license.map(License::activatedAt).map(DISPLAY_FORMAT::format).orElse("-"));
        leaseExpiresLabel.setText(license.map(License::leaseExpiresAt).map(DISPLAY_FORMAT::format).orElse("-"));
        lastContactLabel.setText(license.map(this::describeLastContact).orElse("-"));
    }

    private String describeLastContact(License license) {
        if (license.lastContactAt() == null) {
            return "-";
        }
        String when = DISPLAY_FORMAT.format(license.lastContactAt());
        String result = license.lastContactResult();
        return result == null || result.isBlank() ? when : when + " (" + result + ")";
    }

    @FXML
    private void onCheckNowClicked() {
        errorLabel.setText("");
        setBusy(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                licenseService.refreshLease();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            reload();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            reload();
            errorLabel.setText("Could not reach the licence server. Check the internet connection.");
        });
        Thread thread = new Thread(task, "settings-license-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy) {
        progressIndicator.setVisible(busy);
        checkNowButton.setDisable(busy);
    }
}
