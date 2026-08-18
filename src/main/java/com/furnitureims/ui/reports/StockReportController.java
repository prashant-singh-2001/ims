package com.furnitureims.ui.reports;

import com.furnitureims.domain.Category;
import com.furnitureims.domain.StorageLocation;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ReportService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.ui.catalogue.PieceRegisterController;
import com.furnitureims.util.CsvWriter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Stock on hand, valuation and aging (FR-RPT-01/02; docs/03-screens.md 8.1). */
@Component
public class StockReportController {

    private final ReportService reportService;
    private final CategoryService categoryService;
    private final StorageLocationService storageLocationService;
    private final PieceRegisterController pieceRegisterController;
    private final SceneRouter sceneRouter;

    @FXML private ComboBox<Category> categoryFilterCombo;
    @FXML private ComboBox<StorageLocation> locationFilterCombo;
    @FXML private ComboBox<ReportService.AgingBucket> agingBucketFilterCombo;
    @FXML private Label errorLabel;

    @FXML private Label age0To30Label;
    @FXML private Label age31To60Label;
    @FXML private Label age61To90Label;
    @FXML private Label age90PlusLabel;
    @FXML private Label grandTotalLabel;
    @FXML private Label totalPieceCountLabel;

    @FXML private TableView<StockReportRow> table;
    @FXML private TableColumn<StockReportRow, String> categoryColumn;
    @FXML private TableColumn<StockReportRow, String> modelColumn;
    @FXML private TableColumn<StockReportRow, String> modelCodeColumn;
    @FXML private TableColumn<StockReportRow, Number> pieceCountColumn;
    @FXML private TableColumn<StockReportRow, String> totalValueColumn;
    @FXML private TableColumn<StockReportRow, Number> oldestAgeColumn;
    @FXML private TableColumn<StockReportRow, Void> actionsColumn;

    private ReportService.StockValuationReport currentReport;

    public StockReportController(ReportService reportService, CategoryService categoryService,
                                  StorageLocationService storageLocationService,
                                  PieceRegisterController pieceRegisterController, SceneRouter sceneRouter) {
        this.reportService = reportService;
        this.categoryService = categoryService;
        this.storageLocationService = storageLocationService;
        this.pieceRegisterController = pieceRegisterController;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryFilterCombo.getItems().add(null);
        categoryFilterCombo.getItems().addAll(categoryService.listActive());
        categoryFilterCombo.setConverter(nameConverter(Category::name, "All categories"));

        locationFilterCombo.getItems().add(null);
        locationFilterCombo.getItems().addAll(storageLocationService.listActive());
        locationFilterCombo.setConverter(nameConverter(StorageLocation::name, "All locations"));

        agingBucketFilterCombo.getItems().add(null);
        agingBucketFilterCombo.getItems().addAll(ReportService.AgingBucket.values());
        agingBucketFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ReportService.AgingBucket bucket) {
                return bucket == null ? "All ages" : ReportRowFormatting.bucketLabel(bucket);
            }

            @Override
            public ReportService.AgingBucket fromString(String string) {
                return null;
            }
        });

        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("categoryName"));
        modelColumn.setCellValueFactory(new PropertyValueFactory<>("modelName"));
        modelCodeColumn.setCellValueFactory(new PropertyValueFactory<>("modelCode"));
        pieceCountColumn.setCellValueFactory(new PropertyValueFactory<>("pieceCount"));
        totalValueColumn.setCellValueFactory(new PropertyValueFactory<>("totalValue"));
        oldestAgeColumn.setCellValueFactory(new PropertyValueFactory<>("oldestAgeDays"));
        actionsColumn.setCellFactory(col -> viewPiecesCell());

        errorLabel.setText("");
        reload();
    }

    private static <T> StringConverter<T> nameConverter(java.util.function.Function<T, String> nameFn,
                                                          String allLabel) {
        return new StringConverter<>() {
            @Override
            public String toString(T item) {
                return item == null ? allLabel : nameFn.apply(item);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    private TableCell<StockReportRow, Void> viewPiecesCell() {
        return new TableCell<>() {
            private final Button viewButton = new Button("View Pieces");

            {
                viewButton.setOnAction(e -> {
                    StockReportRow row = getTableView().getItems().get(getIndex());
                    pieceRegisterController.openWithFilters(row.getCategoryId(), row.getModelCode());
                    sceneRouter.navigate(Route.PIECE_REGISTER);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : viewButton);
            }
        };
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        Long categoryId = categoryFilterCombo.getValue() == null ? null : categoryFilterCombo.getValue().id();
        Long locationId = locationFilterCombo.getValue() == null ? null : locationFilterCombo.getValue().id();
        currentReport = reportService.stockValuationReport(categoryId, locationId, agingBucketFilterCombo.getValue());

        List<StockReportRow> rows = currentReport.rows().stream().map(StockReportRow::new).toList();
        table.setItems(FXCollections.observableArrayList(rows));

        ReportService.AgingBucketTotals aging = currentReport.aging();
        age0To30Label.setText(aging.count0To30() + " pieces - " + aging.value0To30().toDisplayString());
        age31To60Label.setText(aging.count31To60() + " pieces - " + aging.value31To60().toDisplayString());
        age61To90Label.setText(aging.count61To90() + " pieces - " + aging.value61To90().toDisplayString());
        age90PlusLabel.setText(aging.count90Plus() + " pieces - " + aging.value90Plus().toDisplayString());

        grandTotalLabel.setText(currentReport.grandTotalValue().toDisplayString());
        totalPieceCountLabel.setText(String.valueOf(currentReport.totalPieceCount()));
    }

    @FXML
    private void onExportCsvClicked() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Stock Report");
        chooser.setInitialFileName("stock-report.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog(table.getScene().getWindow());
        if (file == null || currentReport == null) {
            return;
        }
        List<String> headers = List.of("Category", "Model", "Model Code", "Piece Count", "Total Value",
                "Oldest Age (days)");
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.StockValuationRow row : currentReport.rows()) {
            rows.add(List.of(row.categoryName(), row.modelName(), row.modelCode(),
                    String.valueOf(row.pieceCount()), row.totalLandedValue().toDisplayString(),
                    String.valueOf(row.oldestAgeDays())));
        }
        CsvWriter.write(Path.of(file.getPath()), headers, rows);
        errorLabel.setText("Exported to " + file.getName());
    }

}
