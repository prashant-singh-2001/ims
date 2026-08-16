package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.Category;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.StorageLocation;
import com.furnitureims.repository.PieceSearchCriteria;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Piece register (FR-PIECE-06/07/08; docs/03-screens.md 4.3). */
@Component
public class PieceRegisterController {

    private final PieceService pieceService;
    private final CategoryService categoryService;
    private final StorageLocationService storageLocationService;
    private final PieceDetailController pieceDetailController;
    private final SceneRouter sceneRouter;

    @FXML private TextField searchField;
    @FXML private ComboBox<Piece.State> stateFilterCombo;
    @FXML private ComboBox<Category> categoryFilterCombo;
    @FXML private ComboBox<StorageLocation> locationFilterCombo;
    @FXML private DatePicker acquiredFromPicker;
    @FXML private DatePicker acquiredToPicker;
    @FXML private Label errorLabel;

    @FXML private TableView<PieceRow> table;
    @FXML private TableColumn<PieceRow, String> tagColumn;
    @FXML private TableColumn<PieceRow, String> modelColumn;
    @FXML private TableColumn<PieceRow, String> categoryColumn;
    @FXML private TableColumn<PieceRow, String> stateColumn;
    @FXML private TableColumn<PieceRow, String> locationColumn;
    @FXML private TableColumn<PieceRow, String> costColumn;
    @FXML private TableColumn<PieceRow, Long> daysColumn;
    @FXML private TableColumn<PieceRow, String> sourceColumn;
    @FXML private TableColumn<PieceRow, Void> actionsColumn;

    public PieceRegisterController(PieceService pieceService, CategoryService categoryService,
                                    StorageLocationService storageLocationService,
                                    PieceDetailController pieceDetailController, SceneRouter sceneRouter) {
        this.pieceService = pieceService;
        this.categoryService = categoryService;
        this.storageLocationService = storageLocationService;
        this.pieceDetailController = pieceDetailController;
        this.sceneRouter = sceneRouter;
    }

    private Long pendingCategoryId;
    private String pendingModelSearch;

    /** Drill-through from the stock report (FR-RPT-01): pre-sets the category filter and,
     *  for model-level precision, reuses the free-text search field (it already matches
     *  model name/code via LIKE) rather than adding a dedicated model combo just for this. */
    public void openWithFilters(Long categoryId, String modelSearchText) {
        this.pendingCategoryId = categoryId;
        this.pendingModelSearch = modelSearchText;
    }

    @FXML
    private void initialize() {
        tagColumn.setCellValueFactory(new PropertyValueFactory<>("tag"));
        modelColumn.setCellValueFactory(new PropertyValueFactory<>("modelName"));
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("categoryName"));
        stateColumn.setCellValueFactory(new PropertyValueFactory<>("state"));
        locationColumn.setCellValueFactory(new PropertyValueFactory<>("locationName"));
        costColumn.setCellValueFactory(new PropertyValueFactory<>("landedCost"));
        daysColumn.setCellValueFactory(new PropertyValueFactory<>("daysInStock"));
        sourceColumn.setCellValueFactory(new PropertyValueFactory<>("source"));
        actionsColumn.setCellFactory(col -> actionsCell());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        stateFilterCombo.getItems().add(null);
        stateFilterCombo.getItems().addAll(Piece.State.values());
        stateFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Piece.State state) {
                return state == null ? "All states" : state.name();
            }

            @Override
            public Piece.State fromString(String string) {
                return null;
            }
        });

        categoryFilterCombo.setItems(FXCollections.observableArrayList(categoryService.listActive()));
        categoryFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Category category) {
                return category == null ? "All categories" : category.name();
            }

            @Override
            public Category fromString(String string) {
                return null;
            }
        });

        locationFilterCombo.setItems(FXCollections.observableArrayList(storageLocationService.listActive()));
        locationFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(StorageLocation location) {
                return location == null ? "All locations" : location.name();
            }

            @Override
            public StorageLocation fromString(String string) {
                return null;
            }
        });

        if (pendingCategoryId != null) {
            categoryFilterCombo.getItems().stream()
                    .filter(c -> c != null && c.id() == pendingCategoryId).findFirst()
                    .ifPresent(categoryFilterCombo::setValue);
            pendingCategoryId = null;
        }
        if (pendingModelSearch != null) {
            searchField.setText(pendingModelSearch);
            pendingModelSearch = null;
        }

        errorLabel.setText("");
        reload();
    }

    private TableCell<PieceRow, Void> actionsCell() {
        return new TableCell<>() {
            private final Button viewButton = new Button("View");
            private final Button locationButton = new Button("Change Location");
            private final Button damagedButton = new Button("Mark Damaged");
            private final Button repairButton = new Button("Mark Repaired");
            private final Button writeOffButton = new Button("Write Off");
            private final HBox box = new HBox(6);

            {
                viewButton.setOnAction(e -> onView(rowAt()));
                locationButton.setOnAction(e -> onChangeLocation(List.of(rowAt())));
                damagedButton.setOnAction(e -> onMarkDamaged(rowAt()));
                repairButton.setOnAction(e -> applyStateChange(rowAt().getId(), Piece.State.IN_STOCK, null));
                writeOffButton.setOnAction(e -> onWriteOff(rowAt()));
            }

            private PieceRow rowAt() {
                return getTableView().getItems().get(getIndex());
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                box.getChildren().clear();
                box.getChildren().add(viewButton);
                Piece.State state = rowAt().getSummary().piece().state();
                if (state == Piece.State.IN_STOCK) {
                    box.getChildren().addAll(locationButton, damagedButton, writeOffButton);
                } else if (state == Piece.State.DAMAGED) {
                    box.getChildren().addAll(repairButton, writeOffButton);
                }
                setGraphic(box);
            }
        };
    }

    private void onView(PieceRow row) {
        pieceDetailController.openFor(row.getId());
        sceneRouter.show("/fxml/catalogue/piece-detail.fxml");
    }

    private void onChangeLocation(List<PieceRow> rows) {
        List<StorageLocation> locations = storageLocationService.listActive();
        if (locations.isEmpty()) {
            errorLabel.setText("No active locations - add one under Categories & Locations first.");
            return;
        }
        ChoiceDialog<StorageLocation> dialog = new ChoiceDialog<>(locations.get(0), locations);
        dialog.setTitle("Change Location");
        dialog.setHeaderText(null);
        dialog.setContentText("New location for " + rows.size() + " piece(s):");
        dialog.showAndWait().ifPresent(location -> {
            for (PieceRow row : rows) {
                pieceService.changeLocation(row.getId(), location.id());
            }
            errorLabel.setText("");
            reload();
        });
    }

    private void onMarkDamaged(PieceRow row) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Mark Damaged");
        dialog.setHeaderText(null);
        dialog.setContentText("Reason:");
        dialog.showAndWait().ifPresent(reason -> applyStateChange(row.getId(), Piece.State.DAMAGED, reason));
    }

    private void onWriteOff(PieceRow row) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Write Off");
        dialog.setHeaderText(null);
        dialog.setContentText("Reason:");
        dialog.showAndWait().ifPresent(reason -> applyStateChange(row.getId(), Piece.State.WRITTEN_OFF, reason));
    }

    private void applyStateChange(long pieceId, Piece.State newState, String reason) {
        try {
            pieceService.changeState(pieceId, newState, reason);
            errorLabel.setText("");
            reload();
        } catch (RuntimeException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onBulkChangeLocationClicked() {
        List<PieceRow> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            errorLabel.setText("Select one or more pieces first.");
            return;
        }
        onChangeLocation(selected);
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        PieceSearchCriteria criteria = new PieceSearchCriteria(
                (searchField.getText() == null || searchField.getText().isBlank()) ? null : searchField.getText(),
                stateFilterCombo.getValue(), null,
                categoryFilterCombo.getValue() == null ? null : categoryFilterCombo.getValue().id(),
                locationFilterCombo.getValue() == null ? null : locationFilterCombo.getValue().id(),
                acquiredFromPicker.getValue(), acquiredToPicker.getValue());
        List<PieceRow> rows = pieceService.search(criteria).stream().map(PieceRow::new).toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onOpeningStockClicked() {
        sceneRouter.show("/fxml/catalogue/opening-stock-entry.fxml");
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/shell/dashboard.fxml");
    }
}
