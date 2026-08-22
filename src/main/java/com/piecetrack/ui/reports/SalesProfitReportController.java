package com.piecetrack.ui.reports;

import com.piecetrack.service.ReportService;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import com.piecetrack.util.CsvWriter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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

/** Sales and profit (FR-RPT-03; docs/03-screens.md 8.2). The four breakdown tabs share an
 *  identical column shape, so their {@link TableColumn}s are built once in Java rather than
 *  duplicated four times in FXML. */
@Component
public class SalesProfitReportController {

    private final ReportService reportService;
    private final SceneRouter sceneRouter;

    @FXML private DatePicker fromDatePicker;
    @FXML private DatePicker toDatePicker;
    @FXML private Label errorLabel;

    @FXML private Label summaryInvoiceCountLabel;
    @FXML private Label taxableValueHeading;
    @FXML private Label summaryTaxableValueLabel;
    @FXML private Label taxHeading;
    @FXML private Label summaryTaxLabel;
    @FXML private Label summaryTotalSalesLabel;
    @FXML private Label summaryCostLabel;
    @FXML private Label summaryProfitLabel;
    @FXML private Label summaryMarginLabel;

    @FXML private TabPane breakdownTabs;
    @FXML private TableView<SalesProfitRow> byDayTable;
    @FXML private TableView<SalesProfitRow> byMonthTable;
    @FXML private TableView<SalesProfitRow> byCategoryTable;
    @FXML private TableView<SalesProfitRow> byItemModelTable;

    private ReportService.SalesProfitReport currentReport;
    private LocalDate pendingFrom;
    private LocalDate pendingTo;

    public SalesProfitReportController(ReportService reportService, SceneRouter sceneRouter) {
        this.reportService = reportService;
        this.sceneRouter = sceneRouter;
    }

    /** Drill-through from a dashboard tile (FR-RPT-05): pre-sets the date range instead of
     *  defaulting to "this month". */
    public void openWithDateRange(LocalDate from, LocalDate to) {
        this.pendingFrom = from;
        this.pendingTo = to;
    }

    @FXML
    private void initialize() {
        setupColumns(byDayTable, "Day");
        setupColumns(byMonthTable, "Month");
        setupColumns(byCategoryTable, "Category");
        setupColumns(byItemModelTable, "Item Model");

        LocalDate today = LocalDate.now();
        fromDatePicker.setValue(pendingFrom != null ? pendingFrom : today.withDayOfMonth(1));
        toDatePicker.setValue(pendingTo != null ? pendingTo : today);
        pendingFrom = null;
        pendingTo = null;

        errorLabel.setText("");
        reload();
    }

    private static void setupColumns(TableView<SalesProfitRow> table, String labelHeader) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<SalesProfitRow, String> labelCol = new TableColumn<>(labelHeader);
        labelCol.setCellValueFactory(new PropertyValueFactory<>("label"));
        labelCol.setPrefWidth(140);

        TableColumn<SalesProfitRow, Number> invoiceCountCol = new TableColumn<>("Invoices");
        invoiceCountCol.setCellValueFactory(new PropertyValueFactory<>("invoiceCount"));
        invoiceCountCol.setPrefWidth(80);

        TableColumn<SalesProfitRow, String> taxableCol = new TableColumn<>("Taxable Value");
        taxableCol.setCellValueFactory(new PropertyValueFactory<>("taxableValue"));
        taxableCol.setPrefWidth(110);

        TableColumn<SalesProfitRow, String> taxCol = new TableColumn<>("Tax");
        taxCol.setCellValueFactory(new PropertyValueFactory<>("tax"));
        taxCol.setPrefWidth(90);

        TableColumn<SalesProfitRow, String> totalSalesCol = new TableColumn<>("Total Sales");
        totalSalesCol.setCellValueFactory(new PropertyValueFactory<>("totalSales"));
        totalSalesCol.setPrefWidth(110);

        TableColumn<SalesProfitRow, String> costCol = new TableColumn<>("Cost");
        costCol.setCellValueFactory(new PropertyValueFactory<>("cost"));
        costCol.setPrefWidth(100);

        TableColumn<SalesProfitRow, String> profitCol = new TableColumn<>("Gross Profit");
        profitCol.setCellValueFactory(new PropertyValueFactory<>("profit"));
        profitCol.setPrefWidth(100);

        TableColumn<SalesProfitRow, String> marginCol = new TableColumn<>("Margin %");
        marginCol.setCellValueFactory(new PropertyValueFactory<>("marginPercent"));
        marginCol.setPrefWidth(80);

        table.getColumns().setAll(List.of(labelCol, invoiceCountCol, taxableCol, taxCol, totalSalesCol, costCol,
                profitCol, marginCol));
    }

    @FXML
    private void onTodayPresetClicked() {
        LocalDate today = LocalDate.now();
        fromDatePicker.setValue(today);
        toDatePicker.setValue(today);
        reload();
    }

    @FXML
    private void onThisMonthPresetClicked() {
        LocalDate today = LocalDate.now();
        fromDatePicker.setValue(today.withDayOfMonth(1));
        toDatePicker.setValue(today);
        reload();
    }

    @FXML
    private void onLastMonthPresetClicked() {
        LocalDate firstOfThisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate lastMonth = firstOfThisMonth.minusMonths(1);
        fromDatePicker.setValue(lastMonth);
        toDatePicker.setValue(firstOfThisMonth.minusDays(1));
        reload();
    }

    @FXML
    private void onThisFinancialYearPresetClicked() {
        LocalDate today = LocalDate.now();
        LocalDate fyStart = today.getMonthValue() >= 4
                ? LocalDate.of(today.getYear(), 4, 1)
                : LocalDate.of(today.getYear() - 1, 4, 1);
        fromDatePicker.setValue(fyStart);
        toDatePicker.setValue(today);
        reload();
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        LocalDate from = fromDatePicker.getValue();
        LocalDate to = toDatePicker.getValue();
        if (from == null || to == null) {
            errorLabel.setText("Select both dates.");
            return;
        }
        if (from.isAfter(to)) {
            errorLabel.setText("The from date must not be after the to date.");
            return;
        }
        errorLabel.setText("");

        currentReport = reportService.salesAndProfitReport(from, to);
        ReportService.SalesProfitAggregate summary = currentReport.summary();

        summaryInvoiceCountLabel.setText(String.valueOf(summary.invoiceCount()));
        summaryTaxableValueLabel.setText(summary.taxableValue().toDisplayString());
        summaryTaxLabel.setText(summary.tax().toDisplayString());
        summaryTotalSalesLabel.setText(summary.totalSales().toDisplayString());

        // M10: driven by whether this date range actually had any tax, not the live
        // Settings toggle - a range spanning a GST-on period must keep showing it even if
        // GST has since been turned off (same reasoning as InvoiceDetailController).
        boolean hadTax = summary.tax().isPositive();
        taxableValueHeading.setText(hadTax ? "Taxable value" : "Subtotal");
        taxHeading.setVisible(hadTax);
        taxHeading.setManaged(hadTax);
        summaryTaxLabel.setVisible(hadTax);
        summaryTaxLabel.setManaged(hadTax);
        summaryCostLabel.setText(summary.cost().toDisplayString());
        summaryProfitLabel.setText(summary.profit().toDisplayString());
        summaryMarginLabel.setText(summary.marginPercent().toPlainString() + "%");

        byDayTable.setItems(toRows(currentReport.byDay()));
        byMonthTable.setItems(toRows(currentReport.byMonth()));
        byCategoryTable.setItems(toRows(currentReport.byCategory()));
        byItemModelTable.setItems(toRows(currentReport.byItemModel()));
    }

    private static javafx.collections.ObservableList<SalesProfitRow> toRows(
            List<ReportService.SalesProfitAggregate> aggregates) {
        return FXCollections.observableArrayList(aggregates.stream().map(SalesProfitRow::new).toList());
    }

    @FXML
    private void onExportCsvClicked() {
        if (currentReport == null) {
            return;
        }
        Tab selected = breakdownTabs.getSelectionModel().getSelectedItem();
        List<ReportService.SalesProfitAggregate> data = switch (breakdownTabs.getSelectionModel().getSelectedIndex()) {
            case 0 -> currentReport.byDay();
            case 1 -> currentReport.byMonth();
            case 2 -> currentReport.byCategory();
            default -> currentReport.byItemModel();
        };

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Sales and Profit Report");
        chooser.setInitialFileName("sales-profit-" + (selected == null ? "report" : selected.getText().toLowerCase())
                + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(breakdownTabs.getScene().getWindow());
        if (file == null) {
            return;
        }

        List<String> headers = List.of("Group", "Invoices", "Taxable Value", "Tax", "Total Sales", "Cost",
                "Gross Profit", "Margin %");
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.SalesProfitAggregate a : data) {
            rows.add(List.of(a.label(), String.valueOf(a.invoiceCount()), a.taxableValue().toDisplayString(),
                    a.tax().toDisplayString(), a.totalSales().toDisplayString(), a.cost().toDisplayString(),
                    a.profit().toDisplayString(), a.marginPercent().toPlainString() + "%"));
        }
        CsvWriter.write(Path.of(file.getPath()), headers, rows);
        errorLabel.setText("Exported to " + file.getName());
    }

}
