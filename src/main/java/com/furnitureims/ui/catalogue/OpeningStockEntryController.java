package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.StorageLocation;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ItemModelSearchCriteria;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opening stock entry (FR-PIECE-09; docs/03-screens.md 4.5) - the longest-lead activity
 * for go-live, since it is how the shop's existing stock gets keyed in before the system
 * is otherwise usable for real sales.
 * <p>
 * Rows are built programmatically rather than as editable TableView cells: with five
 * different control types per row (model combo, quantity spinner, cost field, location
 * combo, date picker), a plain HBox per row is far simpler than the custom cell factories
 * TableView editing would need for the same mix.
 */
@Component
public class OpeningStockEntryController {

    private record RowControls(ComboBox<ItemModel> modelCombo, Spinner<Integer> quantitySpinner,
                                TextField costField, ComboBox<StorageLocation> locationCombo,
                                DatePicker acquiredPicker, HBox container) {
    }

    private record PendingEntry(ItemModel model, int quantity, Money cost, StorageLocation location,
                                 LocalDate acquiredOn) {
    }

    private final PieceService pieceService;
    private final ItemModelService itemModelService;
    private final StorageLocationService storageLocationService;
    private final SceneRouter sceneRouter;

    @FXML private VBox rowsBox;
    @FXML private Label errorLabel;

    private final List<RowControls> rows = new ArrayList<>();

    public OpeningStockEntryController(PieceService pieceService, ItemModelService itemModelService,
                                        StorageLocationService storageLocationService, SceneRouter sceneRouter) {
        this.pieceService = pieceService;
        this.itemModelService = itemModelService;
        this.storageLocationService = storageLocationService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        resetRows();
    }

    private void resetRows() {
        rows.clear();
        rowsBox.getChildren().clear();
        errorLabel.setText("");
        addRow();
    }

    @FXML
    private void onAddRowClicked() {
        addRow();
    }

    private void addRow() {
        List<ItemModel> models = itemModelService.search(ItemModelSearchCriteria.defaultCriteria())
                .stream().map(summary -> summary.model()).toList();

        ComboBox<ItemModel> modelCombo = new ComboBox<>(FXCollections.observableArrayList(models));
        modelCombo.setPrefWidth(220);
        modelCombo.setPromptText("Item model");
        modelCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ItemModel m) {
                return m == null ? "" : m.modelName() + " (" + m.modelCode() + ")";
            }

            @Override
            public ItemModel fromString(String s) {
                return null;
            }
        });

        Spinner<Integer> quantitySpinner = new Spinner<>(1, 999, 1);
        quantitySpinner.setEditable(true);
        quantitySpinner.setPrefWidth(80);

        TextField costField = new TextField();
        costField.setPromptText("Cost per piece (Rs)");
        costField.setPrefWidth(130);

        ComboBox<StorageLocation> locationCombo = new ComboBox<>(
                FXCollections.observableArrayList(storageLocationService.listActive()));
        locationCombo.setPrefWidth(150);
        locationCombo.setPromptText("Location");
        locationCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(StorageLocation l) {
                return l == null ? "" : l.name();
            }

            @Override
            public StorageLocation fromString(String s) {
                return null;
            }
        });

        DatePicker acquiredPicker = new DatePicker(LocalDate.now());
        acquiredPicker.setPrefWidth(140);

        Button removeButton = new Button("Remove");

        HBox rowBox = new HBox(8, modelCombo, quantitySpinner, costField, locationCombo, acquiredPicker, removeButton);
        rowBox.setAlignment(Pos.CENTER_LEFT);

        RowControls controls = new RowControls(modelCombo, quantitySpinner, costField, locationCombo,
                acquiredPicker, rowBox);
        removeButton.setOnAction(e -> {
            rows.remove(controls);
            rowsBox.getChildren().remove(rowBox);
        });

        rows.add(controls);
        rowsBox.getChildren().add(rowBox);
    }

    @FXML
    private void onSaveAllClicked() {
        List<PendingEntry> pending = new ArrayList<>();
        long totalPaisa = 0;
        int totalPieces = 0;

        for (RowControls row : rows) {
            ItemModel model = row.modelCombo().getValue();
            if (model == null) {
                continue;
            }
            int quantity = row.quantitySpinner().getValue();
            String costText = row.costField().getText();
            if (costText == null || costText.isBlank()) {
                errorLabel.setText("Enter a per-piece cost for " + model.modelName() + ".");
                return;
            }
            Money cost;
            try {
                cost = Money.ofRupees(costText.trim());
            } catch (NumberFormatException e) {
                errorLabel.setText("Invalid cost for " + model.modelName() + ".");
                return;
            }
            StorageLocation location = row.locationCombo().getValue();
            LocalDate acquiredOn = row.acquiredPicker().getValue() == null ? LocalDate.now() : row.acquiredPicker().getValue();

            pending.add(new PendingEntry(model, quantity, cost, location, acquiredOn));
            totalPieces += quantity;
            totalPaisa += cost.paisa() * quantity;
        }

        if (pending.isEmpty()) {
            errorLabel.setText("Add at least one row with a model selected.");
            return;
        }

        Money totalValue = Money.ofPaisa(totalPaisa);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Opening Stock");
        confirm.setHeaderText(null);
        confirm.setContentText("This will create " + totalPieces + " pieces worth "
                + totalValue.toDisplayString() + " total. Continue?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        List<String> generatedTags = new ArrayList<>();
        for (PendingEntry entry : pending) {
            Long locationId = entry.location() == null ? null : entry.location().id();
            List<Piece> created = pieceService.createOpeningStock(
                    entry.model().id(), entry.quantity(), entry.cost(), locationId, entry.acquiredOn());
            for (Piece piece : created) {
                generatedTags.add(piece.tag());
            }
        }

        errorLabel.setText("");
        showGeneratedTags(generatedTags);
        resetRows();
    }

    private void showGeneratedTags(List<String> tags) {
        TextArea area = new TextArea(String.join("\n", tags));
        area.setEditable(false);
        area.setPrefRowCount(15);
        area.setPrefWidth(300);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Opening Stock Created");
        alert.setHeaderText(tags.size() + " piece(s) created. Write these tags onto the furniture:");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/catalogue/piece-register.fxml");
    }
}
