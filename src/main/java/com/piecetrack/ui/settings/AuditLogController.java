package com.piecetrack.ui.settings;

import com.piecetrack.domain.AuditLog;
import com.piecetrack.repository.AuditLogRepository;
import com.piecetrack.repository.AuditLogSearchCriteria;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import com.piecetrack.util.CsvWriter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-SYS-03: viewable and exportable, never editable from within the application - there is
 * deliberately no edit or delete action anywhere on this screen, matching
 * {@link AuditLogRepository} having no update/delete methods to call.
 */
@Component
public class AuditLogController {

    /** The screen's own cap - the export button re-queries with no limit, since exporting
     *  "everything in range" is the whole point of a CSV export. */
    private static final int SCREEN_ROW_LIMIT = 1000;

    private final AuditLogRepository auditLogRepository;
    private final SceneRouter sceneRouter;

    @FXML private DatePicker dateFromPicker;
    @FXML private DatePicker dateToPicker;
    @FXML private ComboBox<String> actionFilterCombo;
    @FXML private Label statusLabel;
    @FXML private TableView<AuditLogRow> table;

    private List<AuditLog> currentRows = List.of();

    public AuditLogController(AuditLogRepository auditLogRepository, SceneRouter sceneRouter) {
        this.auditLogRepository = auditLogRepository;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        actionFilterCombo.getItems().add(null);
        actionFilterCombo.getItems().addAll(auditLogRepository.distinctActions());
        actionFilterCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String action) {
                return action == null ? "All actions" : action;
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });

        setupColumns();
        statusLabel.setText("");
        reload();
    }

    private void setupColumns() {
        TableColumn<AuditLogRow, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("loggedAt"));
        timeCol.setPrefWidth(150);
        TableColumn<AuditLogRow, String> userCol = new TableColumn<>("User");
        userCol.setCellValueFactory(new PropertyValueFactory<>("username"));
        userCol.setPrefWidth(100);
        TableColumn<AuditLogRow, String> actionCol = new TableColumn<>("Action");
        actionCol.setCellValueFactory(new PropertyValueFactory<>("action"));
        actionCol.setPrefWidth(160);
        TableColumn<AuditLogRow, String> entityCol = new TableColumn<>("Entity");
        entityCol.setCellValueFactory(new PropertyValueFactory<>("entity"));
        entityCol.setPrefWidth(150);
        TableColumn<AuditLogRow, String> beforeCol = new TableColumn<>("Before");
        beforeCol.setCellValueFactory(new PropertyValueFactory<>("beforeJson"));
        beforeCol.setPrefWidth(200);
        TableColumn<AuditLogRow, String> afterCol = new TableColumn<>("After");
        afterCol.setCellValueFactory(new PropertyValueFactory<>("afterJson"));
        afterCol.setPrefWidth(200);
        TableColumn<AuditLogRow, String> noteCol = new TableColumn<>("Note");
        noteCol.setCellValueFactory(new PropertyValueFactory<>("note"));
        noteCol.setPrefWidth(200);

        table.getColumns().setAll(List.of(timeCol, userCol, actionCol, entityCol, beforeCol, afterCol, noteCol));
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        currentRows = auditLogRepository.search(criteriaFromFilters(), SCREEN_ROW_LIMIT);
        table.setItems(FXCollections.observableArrayList(currentRows.stream().map(AuditLogRow::new).toList()));
        statusLabel.setText(currentRows.size() >= SCREEN_ROW_LIMIT
                ? "Showing the most recent " + SCREEN_ROW_LIMIT + " entries - narrow the date range to see more, "
                        + "or export CSV for the full range."
                : currentRows.size() + " entries.");
    }

    private AuditLogSearchCriteria criteriaFromFilters() {
        LocalDate from = dateFromPicker.getValue();
        LocalDate to = dateToPicker.getValue();
        String action = actionFilterCombo.getValue();
        return new AuditLogSearchCriteria(from, to, action, null);
    }

    @FXML
    private void onExportCsvClicked() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Audit Log");
        chooser.setInitialFileName("audit-log.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(table.getScene().getWindow());
        if (file == null) {
            return;
        }

        List<AuditLog> fullRange = auditLogRepository.search(criteriaFromFilters(), 0);
        List<String> headers = List.of("Time", "User", "Action", "Entity Type", "Entity ID", "Before", "After", "Note");
        List<List<String>> rows = new ArrayList<>();
        for (AuditLog a : fullRange) {
            rows.add(List.of(a.loggedAt().toString(), nullToEmpty(a.username()), a.action(),
                    nullToEmpty(a.entityType()), a.entityId() == null ? "" : a.entityId().toString(),
                    nullToEmpty(a.beforeJson()), nullToEmpty(a.afterJson()), nullToEmpty(a.note())));
        }
        CsvWriter.write(Path.of(file.getPath()), headers, rows);
        statusLabel.setText("Exported " + fullRange.size() + " entries to " + file.getName());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Flattens {@link AuditLog} for {@link PropertyValueFactory}, which needs plain getters
     *  and cannot see a record's accessor methods directly. */
    public static final class AuditLogRow {
        private final AuditLog log;

        AuditLogRow(AuditLog log) {
            this.log = log;
        }

        public String getLoggedAt() {
            return log.loggedAt().toString().replace('T', ' ');
        }

        public String getUsername() {
            return log.username() == null ? "(system)" : log.username();
        }

        public String getAction() {
            return log.action();
        }

        public String getEntity() {
            if (log.entityType() == null) {
                return "";
            }
            return log.entityId() == null ? log.entityType() : log.entityType() + " #" + log.entityId();
        }

        public String getBeforeJson() {
            return log.beforeJson() == null ? "" : log.beforeJson();
        }

        public String getAfterJson() {
            return log.afterJson() == null ? "" : log.afterJson();
        }

        public String getNote() {
            return log.note() == null ? "" : log.note();
        }
    }
}
