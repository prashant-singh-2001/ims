package com.piecetrack.ui.reports;

import com.piecetrack.money.Money;
import com.piecetrack.service.ReportService;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import com.piecetrack.util.CsvWriter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Outstanding dues and aging, both directions (FR-RPT-04; docs/03-screens.md 8.3). */
@Component
public class DuesReportController {

    private final ReportService reportService;
    private final SceneRouter sceneRouter;

    @FXML private ComboBox<ReportService.AgingBucket> bucketFilterCombo;
    @FXML private TextField minimumAmountField;
    @FXML private TextField searchField;
    @FXML private Label errorLabel;

    @FXML private TabPane tabs;
    @FXML private TableView<CustomerDueRow> customerTable;
    @FXML private TableView<SupplierDueRow> supplierTable;

    private List<ReportService.CustomerDueRow> currentCustomerDues = List.of();
    private List<ReportService.SupplierDueRow> currentSupplierDues = List.of();
    private Integer pendingTabIndex;

    public DuesReportController(ReportService reportService, SceneRouter sceneRouter) {
        this.reportService = reportService;
        this.sceneRouter = sceneRouter;
    }

    /** Drill-through from the Payable dashboard tile (FR-RPT-05): opens straight to the
     *  supplier tab instead of the default customer one. */
    public void openWithTab(int tabIndex) {
        this.pendingTabIndex = tabIndex;
    }

    @FXML
    private void initialize() {
        customerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        supplierTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        bucketFilterCombo.getItems().add(null);
        bucketFilterCombo.getItems().addAll(ReportService.AgingBucket.values());
        bucketFilterCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ReportService.AgingBucket bucket) {
                return bucket == null ? "All ages" : ReportRowFormatting.bucketLabel(bucket);
            }

            @Override
            public ReportService.AgingBucket fromString(String string) {
                return null;
            }
        });

        setupCustomerColumns();
        setupSupplierColumns();

        if (pendingTabIndex != null) {
            tabs.getSelectionModel().select(pendingTabIndex);
            pendingTabIndex = null;
        }

        errorLabel.setText("");
        reload();
    }

    private void setupCustomerColumns() {
        TableColumn<CustomerDueRow, String> nameCol = new TableColumn<>("Customer");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("customerName"));
        nameCol.setPrefWidth(160);
        TableColumn<CustomerDueRow, String> phoneCol = new TableColumn<>("Phone");
        phoneCol.setCellValueFactory(new PropertyValueFactory<>("customerPhone"));
        phoneCol.setPrefWidth(110);
        TableColumn<CustomerDueRow, String> invoiceCol = new TableColumn<>("Invoice #");
        invoiceCol.setCellValueFactory(new PropertyValueFactory<>("invoiceNo"));
        invoiceCol.setPrefWidth(130);
        TableColumn<CustomerDueRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("invoiceDate"));
        dateCol.setPrefWidth(100);
        TableColumn<CustomerDueRow, String> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(new PropertyValueFactory<>("grandTotal"));
        totalCol.setPrefWidth(100);
        TableColumn<CustomerDueRow, String> balanceCol = new TableColumn<>("Balance");
        balanceCol.setCellValueFactory(new PropertyValueFactory<>("balance"));
        balanceCol.setPrefWidth(100);
        TableColumn<CustomerDueRow, Number> daysCol = new TableColumn<>("Days");
        daysCol.setCellValueFactory(new PropertyValueFactory<>("daysOutstanding"));
        daysCol.setPrefWidth(70);
        TableColumn<CustomerDueRow, String> bucketCol = new TableColumn<>("Aging");
        bucketCol.setCellValueFactory(new PropertyValueFactory<>("bucket"));
        bucketCol.setPrefWidth(90);

        customerTable.getColumns().setAll(
                List.of(nameCol, phoneCol, invoiceCol, dateCol, totalCol, balanceCol, daysCol, bucketCol));
    }

    private void setupSupplierColumns() {
        TableColumn<SupplierDueRow, String> nameCol = new TableColumn<>("Supplier");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("supplierName"));
        nameCol.setPrefWidth(160);
        TableColumn<SupplierDueRow, String> phoneCol = new TableColumn<>("Phone");
        phoneCol.setCellValueFactory(new PropertyValueFactory<>("supplierPhone"));
        phoneCol.setPrefWidth(110);
        TableColumn<SupplierDueRow, String> billCol = new TableColumn<>("Bill #");
        billCol.setCellValueFactory(new PropertyValueFactory<>("supplierBillNo"));
        billCol.setPrefWidth(130);
        TableColumn<SupplierDueRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("billDate"));
        dateCol.setPrefWidth(100);
        TableColumn<SupplierDueRow, String> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(new PropertyValueFactory<>("grandTotal"));
        totalCol.setPrefWidth(100);
        TableColumn<SupplierDueRow, String> balanceCol = new TableColumn<>("Balance");
        balanceCol.setCellValueFactory(new PropertyValueFactory<>("balance"));
        balanceCol.setPrefWidth(100);
        TableColumn<SupplierDueRow, Number> daysCol = new TableColumn<>("Days");
        daysCol.setCellValueFactory(new PropertyValueFactory<>("daysOutstanding"));
        daysCol.setPrefWidth(70);
        TableColumn<SupplierDueRow, String> bucketCol = new TableColumn<>("Aging");
        bucketCol.setCellValueFactory(new PropertyValueFactory<>("bucket"));
        bucketCol.setPrefWidth(90);

        supplierTable.getColumns().setAll(
                List.of(nameCol, phoneCol, billCol, dateCol, totalCol, balanceCol, daysCol, bucketCol));
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        Money minimum = parseOptionalMoney(minimumAmountField.getText());
        ReportService.AgingBucket bucket = bucketFilterCombo.getValue();

        currentCustomerDues = reportService.customerDuesAging(null, bucket, minimum);
        currentSupplierDues = reportService.supplierDuesAging(null, bucket, minimum);

        String search = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<CustomerDueRow> customerRows = currentCustomerDues.stream()
                .filter(r -> search.isEmpty() || r.customerName().toLowerCase().contains(search)
                        || (r.customerPhone() != null && r.customerPhone().contains(search)))
                .map(CustomerDueRow::new).toList();
        List<SupplierDueRow> supplierRows = currentSupplierDues.stream()
                .filter(r -> search.isEmpty() || r.supplierName().toLowerCase().contains(search)
                        || (r.supplierPhone() != null && r.supplierPhone().contains(search)))
                .map(SupplierDueRow::new).toList();

        customerTable.setItems(FXCollections.observableArrayList(customerRows));
        supplierTable.setItems(FXCollections.observableArrayList(supplierRows));
    }

    private static Money parseOptionalMoney(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Money.ofRupees(new BigDecimal(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @FXML
    private void onExportCsvClicked() {
        boolean customerTabSelected = tabs.getSelectionModel().getSelectedIndex() == 0;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Dues Report");
        chooser.setInitialFileName((customerTabSelected ? "customer-dues" : "supplier-dues") + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(tabs.getScene().getWindow());
        if (file == null) {
            return;
        }

        List<String> headers;
        List<List<String>> rows = new ArrayList<>();
        if (customerTabSelected) {
            headers = List.of("Customer", "Phone", "Invoice #", "Date", "Total", "Balance", "Days", "Aging");
            for (ReportService.CustomerDueRow r : currentCustomerDues) {
                rows.add(List.of(r.customerName(), nullToEmpty(r.customerPhone()), r.invoice().invoiceNo(),
                        r.invoice().invoiceDate().toString(), r.invoice().grandTotal().toDisplayString(),
                        r.balance().toDisplayString(), String.valueOf(r.daysOutstanding()),
                        ReportRowFormatting.bucketLabel(r.bucket())));
            }
        } else {
            headers = List.of("Supplier", "Phone", "Bill #", "Date", "Total", "Balance", "Days", "Aging");
            for (ReportService.SupplierDueRow r : currentSupplierDues) {
                rows.add(List.of(r.supplierName(), nullToEmpty(r.supplierPhone()), r.bill().supplierBillNo(),
                        r.bill().billDate().toString(), r.bill().grandTotal().toDisplayString(),
                        r.balance().toDisplayString(), String.valueOf(r.daysOutstanding()),
                        ReportRowFormatting.bucketLabel(r.bucket())));
            }
        }
        CsvWriter.write(Path.of(file.getPath()), headers, rows);
        errorLabel.setText("Exported to " + file.getName());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
