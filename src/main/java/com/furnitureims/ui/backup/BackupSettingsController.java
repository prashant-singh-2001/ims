package com.furnitureims.ui.backup;

import com.furnitureims.domain.BackupHistory;
import com.furnitureims.repository.BackupHistoryRepository;
import com.furnitureims.service.BackupService;
import com.furnitureims.service.GoogleDriveService;
import com.furnitureims.service.RestoreService;
import com.furnitureims.service.SettingsService;
import com.furnitureims.ui.SceneRouter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Backup and restore (FR-BAK-01..16; docs/03-screens.md section 9). Every long-running
 * operation here (Backup Now, Connect Google, Restore) runs on a background
 * {@link Task} - this is the first screen in the app where an operation can genuinely take
 * more than a moment, and freezing the JavaFX Application Thread for it would be a real
 * regression, not a cosmetic one.
 */
@Component
public class BackupSettingsController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final BackupService backupService;
    private final RestoreService restoreService;
    private final GoogleDriveService googleDriveService;
    private final SettingsService settingsService;
    private final BackupHistoryRepository backupHistoryRepository;
    private final SceneRouter sceneRouter;

    @FXML private Label lastBackupLabel;
    @FXML private Label nextScheduledLabel;
    @FXML private Label googleAccountLabel;
    @FXML private Label archiveStatsLabel;

    @FXML private TextField dailyTimeField;
    @FXML private ComboBox<DayOfWeek> weeklyDayCombo;
    @FXML private TextField retentionDailyField;
    @FXML private TextField retentionWeeklyField;
    @FXML private TextField driveFolderField;
    @FXML private TextField googleClientIdField;
    @FXML private PasswordField googleClientSecretField;
    @FXML private Label settingsStatusLabel;

    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label progressLabel;
    @FXML private Label errorLabel;

    @FXML private TableView<BackupHistoryRow> archiveTable;
    @FXML private TableColumn<BackupHistoryRow, String> dateColumn;
    @FXML private TableColumn<BackupHistoryRow, String> typeColumn;
    @FXML private TableColumn<BackupHistoryRow, String> statusColumn;
    @FXML private TableColumn<BackupHistoryRow, String> sizeColumn;
    @FXML private TableColumn<BackupHistoryRow, String> verifiedColumn;
    @FXML private TableColumn<BackupHistoryRow, String> locationColumn;
    @FXML private TableColumn<BackupHistoryRow, Void> actionsColumn;

    public BackupSettingsController(BackupService backupService, RestoreService restoreService,
                                     GoogleDriveService googleDriveService, SettingsService settingsService,
                                     BackupHistoryRepository backupHistoryRepository, SceneRouter sceneRouter) {
        this.backupService = backupService;
        this.restoreService = restoreService;
        this.googleDriveService = googleDriveService;
        this.settingsService = settingsService;
        this.backupHistoryRepository = backupHistoryRepository;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        weeklyDayCombo.setItems(FXCollections.observableArrayList(DayOfWeek.values()));

        dateColumn.setCellValueFactory(new PropertyValueFactory<>("startedAt"));
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("backupType"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        verifiedColumn.setCellValueFactory(new PropertyValueFactory<>("verified"));
        locationColumn.setCellValueFactory(new PropertyValueFactory<>("location"));
        actionsColumn.setCellFactory(col -> actionsCell());

        errorLabel.setText("");
        settingsStatusLabel.setText("");
        progressIndicator.setVisible(false);
        progressLabel.setText("");

        loadSettings();
        reload();
    }

    private void loadSettings() {
        dailyTimeField.setText(settingsService.backupDailyTime());
        weeklyDayCombo.setValue(parseDay(settingsService.backupWeeklyDay()));
        retentionDailyField.setText(String.valueOf(settingsService.backupRetentionDaily()));
        retentionWeeklyField.setText(String.valueOf(settingsService.backupRetentionWeekly()));
        driveFolderField.setText(settingsService.backupDriveFolderName());
        googleClientIdField.setText(settingsService.googleClientId().orElse(""));
        googleClientSecretField.setText(settingsService.googleClientSecret().orElse(""));
    }

    private void reload() {
        List<BackupHistory> all = backupHistoryRepository.findAllOrderedByStartedDesc();

        lastBackupLabel.setText(all.isEmpty() ? "No backups yet"
                : all.get(0).status() + " at " + all.get(0).startedAt().toString().replace('T', ' '));

        LocalTime scheduledTime = parseTime(settingsService.backupDailyTime());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.toLocalTime().isBefore(scheduledTime)
                ? LocalDateTime.of(now.toLocalDate(), scheduledTime)
                : LocalDateTime.of(now.toLocalDate().plusDays(1), scheduledTime);
        nextScheduledLabel.setText(nextRun.toString().replace('T', ' '));

        googleAccountLabel.setText(googleDriveService.isConnected()
                ? "Connected - folder \"" + settingsService.backupDriveFolderName() + "\""
                : "Not connected");

        long driveCount = all.stream().filter(b -> b.driveFileId() != null).count();
        long driveBytes = all.stream().filter(b -> b.driveFileId() != null && b.sizeBytes() != null)
                .mapToLong(BackupHistory::sizeBytes).sum();
        archiveStatsLabel.setText(driveCount + " archive(s) on Drive, " + String.format("%.1f MB", driveBytes / (1024.0 * 1024.0)));

        List<BackupHistoryRow> rows = all.stream().map(BackupHistoryRow::new).toList();
        archiveTable.setItems(FXCollections.observableArrayList(rows));
    }

    // ---- Settings -------------------------------------------------------------------------

    @FXML
    private void onSaveSettingsClicked() {
        try {
            parseTime(dailyTimeField.getText());
            settingsService.setBackupDailyTime(dailyTimeField.getText().trim());
            settingsService.setBackupWeeklyDay(weeklyDayCombo.getValue().name());
            settingsService.setBackupRetentionDaily(Integer.parseInt(retentionDailyField.getText().trim()));
            settingsService.setBackupRetentionWeekly(Integer.parseInt(retentionWeeklyField.getText().trim()));
            settingsService.setBackupDriveFolderName(driveFolderField.getText().trim());
            settingsService.setGoogleOAuthClient(nullIfBlank(googleClientIdField.getText()),
                    nullIfBlank(googleClientSecretField.getText()));
            settingsStatusLabel.setText("Saved.");
            reload();
        } catch (RuntimeException e) {
            settingsStatusLabel.setText("Daily time must be HH:mm and retention counts must be numbers.");
        }
    }

    @FXML
    private void onChangeBackupPasswordClicked() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Change Backup Password");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        PasswordField newPasswordField = new PasswordField();
        PasswordField confirmField = new PasswordField();
        CheckBox acknowledgeCheck = new CheckBox("I understand a lost backup password cannot be recovered, "
                + "and that archives already made will still need the OLD password.");
        Label warning = new Label("Changing this does not re-encrypt any existing archive.");
        warning.setWrapText(true);
        warning.setMaxWidth(320);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("New password"), newPasswordField);
        grid.addRow(1, new Label("Confirm"), confirmField);
        grid.add(warning, 0, 2, 2, 1);
        grid.add(acknowledgeCheck, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);

        dialog.showAndWait().filter(result -> result == ButtonType.OK).ifPresent(result -> {
            String newPassword = newPasswordField.getText();
            if (newPassword == null || newPassword.length() < 8) {
                showError("Password must be at least 8 characters.");
                return;
            }
            if (!newPassword.equals(confirmField.getText())) {
                showError("Passwords do not match.");
                return;
            }
            if (!acknowledgeCheck.isSelected()) {
                showError("Please confirm you understand the warning.");
                return;
            }
            settingsService.setBackupPassword(newPassword);
            errorLabel.setText("");
            settingsStatusLabel.setText("Backup password changed.");
        });
    }

    // ---- Google Drive connection -----------------------------------------------------------

    @FXML
    private void onConnectGoogleClicked() {
        runInBackground("Opening your browser for Google sign-in...",
                () -> {
                    googleDriveService.connect();
                    return null;
                },
                v -> {
                    reload();
                    showInfo("Google Drive connected.");
                },
                this::showError);
    }

    @FXML
    private void onDisconnectGoogleClicked() {
        googleDriveService.disconnect();
        reload();
    }

    // ---- Backup now -------------------------------------------------------------------------

    @FXML
    private void onBackupNowClicked() {
        Optional<String> password = settingsService.currentBackupPasswordForScheduledRun();
        if (password.isEmpty()) {
            showError("Set a backup password first (see the setup wizard, or Change Backup Password above).");
            return;
        }
        runInBackground("Backing up: snapshot -> compress -> encrypt -> upload -> verify -> prune...",
                () -> backupService.runBackup(BackupHistory.BackupType.MANUAL, password.get().toCharArray()),
                outcome -> {
                    reload();
                    if (outcome.status() == BackupHistory.Status.FAILED) {
                        showError("Backup failed: " + outcome.message());
                    } else if (outcome.status() == BackupHistory.Status.UPLOAD_PENDING) {
                        showInfo("Backup saved locally. It will upload automatically once Drive is reachable.");
                    } else {
                        showInfo("Backup completed and uploaded to Drive.");
                    }
                },
                this::showError);
    }

    // ---- Archive list actions ---------------------------------------------------------------

    private TableCell<BackupHistoryRow, Void> actionsCell() {
        return new TableCell<>() {
            private final Button restoreButton = new Button("Restore");
            private final Button deleteButton = new Button("Delete");
            private final HBox box = new HBox(6, restoreButton, deleteButton);

            {
                restoreButton.setOnAction(e -> onRestoreClicked(rowAt()));
                deleteButton.setOnAction(e -> onDeleteClicked(rowAt()));
            }

            private BackupHistoryRow rowAt() {
                return getTableView().getItems().get(getIndex());
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                restoreButton.setDisable(!rowAt().isRestorable());
                setGraphic(box);
            }
        };
    }

    private void onDeleteClicked(BackupHistoryRow row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete this backup archive (" + row.getStartedAt() + ")? This cannot be undone.");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            BackupHistory history = row.getHistory();
            if (history.driveFileId() != null) {
                try {
                    googleDriveService.delete(history.driveFileId());
                } catch (Exception e) {
                    showError("Could not delete from Drive: " + e.getMessage());
                    return;
                }
            }
            if (history.localPath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(history.localPath()));
                } catch (java.io.IOException ignored) {
                    // A leftover local file is not worth blocking the delete over.
                }
            }
            backupHistoryRepository.delete(history.id());
            reload();
        });
    }

    /** Screens.md's deliberately slow, explicit restore flow: confirm -> type RESTORE ->
     *  password -> verify (background) -> final confirmation with app/schema version ->
     *  swap (background) -> tell the owner to restart. */
    private void onRestoreClicked(BackupHistoryRow row) {
        Alert warn = new Alert(Alert.AlertType.WARNING,
                "Restoring " + row.getStartedAt() + " (" + row.getBackupType() + ", " + row.getSize()
                        + ") replaces EVERYTHING currently in the system - all sales, purchases, payments and "
                        + "settings made since that backup will be lost.",
                ButtonType.OK, ButtonType.CANCEL);
        warn.setHeaderText("This cannot be undone from within the app");
        if (warn.showAndWait().filter(bt -> bt == ButtonType.OK).isEmpty()) {
            return;
        }

        TextInputDialog typeConfirm = new TextInputDialog();
        typeConfirm.setHeaderText(null);
        typeConfirm.setContentText("Type RESTORE to confirm:");
        Optional<String> typed = typeConfirm.showAndWait();
        if (typed.isEmpty() || !"RESTORE".equals(typed.get().trim())) {
            showError("Restore cancelled - you must type RESTORE exactly.");
            return;
        }

        Optional<String> password = promptForPassword("Backup Password",
                "Enter the backup password used for this archive:");
        if (password.isEmpty()) {
            return;
        }

        runInBackground("Downloading and verifying the archive...",
                () -> restoreService.prepareRestore(row.getId(), password.get().toCharArray()),
                this::confirmAndPerformRestore,
                this::showError);
    }

    private void confirmAndPerformRestore(RestoreService.RestorePreview preview) {
        Alert finalConfirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Archive verified.\nApp version: " + preview.manifest().appVersion()
                        + "\nSchema version: " + preview.manifest().schemaVersion()
                        + "\nCreated: " + preview.manifest().createdAt()
                        + "\n\nThis is the last chance to cancel before your current data is replaced.",
                ButtonType.OK, ButtonType.CANCEL);
        finalConfirm.setHeaderText("Ready to restore");
        if (finalConfirm.showAndWait().filter(bt -> bt == ButtonType.OK).isEmpty()) {
            return;
        }

        runInBackground("Restoring: safety-copying current data, then swapping in the restored data...",
                () -> restoreService.performRestore(preview),
                safetyCopyPath -> {
                    Alert done = new Alert(Alert.AlertType.INFORMATION);
                    done.setHeaderText("Restore complete");
                    done.setContentText("Your previous data was saved to:\n" + safetyCopyPath
                            + "\n\nPlease close and reopen the application now.");
                    ButtonType closeNow = new ButtonType("Close Application");
                    done.getButtonTypes().setAll(closeNow, ButtonType.CLOSE);
                    done.showAndWait().filter(bt -> bt == closeNow).ifPresent(bt -> Platform.exit());
                },
                this::showError);
    }

    // ---- Background task helper -------------------------------------------------------------

    private <T> void runInBackground(String progressMessage, java.util.concurrent.Callable<T> work,
                                      java.util.function.Consumer<T> onSuccess,
                                      java.util.function.Consumer<String> onFailure) {
        setBusy(true, progressMessage);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false, "");
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false, "");
            Throwable ex = task.getException();
            onFailure.accept(ex == null || ex.getMessage() == null ? "An unexpected error occurred." : ex.getMessage());
        });
        Thread thread = new Thread(task, "backup-ui-task");
        thread.setDaemon(true);
        thread.start();
    }

    private void setBusy(boolean busy, String message) {
        progressIndicator.setVisible(busy);
        progressLabel.setText(message);
        archiveTable.setDisable(busy);
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }

    private void showInfo(String message) {
        errorLabel.setText("");
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setHeaderText(null);
        info.setContentText(message);
        info.showAndWait();
    }

    private static Optional<String> promptForPassword(String title, String contentText) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField field = new PasswordField();
        HBox box = new HBox(10, new Label(contentText), field);
        dialog.getDialogPane().setContent(box);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? field.getText() : null);
        return dialog.showAndWait().filter(text -> text != null && !text.isBlank());
    }

    private static LocalTime parseTime(String text) {
        return LocalTime.parse(text.trim(), TIME_FORMAT);
    }

    private static DayOfWeek parseDay(String text) {
        try {
            return DayOfWeek.valueOf(text);
        } catch (RuntimeException e) {
            return DayOfWeek.SUNDAY;
        }
    }

    private static String nullIfBlank(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/shell/dashboard.fxml");
    }
}
