package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.Category;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.StorageLocation;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ItemModelSearchCriteria;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SettingsService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.Route;
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
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * <p>
 * M10: a shop with no catalogue yet cannot use the "existing model" row at all - the model
 * combo has nothing to offer. The "+ New Item" row is a free-entry alternative: type a name,
 * a cost and a quantity, and Save All quietly creates the backing {@link ItemModel} (with an
 * auto-generated model code) before creating pieces against it, so every invariant the rest
 * of the app relies on (a piece always belongs to a real, categorised model) still holds -
 * only the UI friction of visiting the catalogue screen first is removed.
 */
@Component
public class OpeningStockEntryController {

    private record RowControls(ComboBox<ItemModel> modelCombo, Spinner<Integer> quantitySpinner,
                                TextField costField, ComboBox<StorageLocation> locationCombo,
                                DatePicker acquiredPicker, HBox container) {
    }

    private record QuickRowControls(TextField nameField, ComboBox<Category> categoryCombo,
                                     TextField costField, TextField salePriceField, TextField hsnField,
                                     TextField gstRateField, Spinner<Integer> quantitySpinner,
                                     ComboBox<StorageLocation> locationCombo, DatePicker acquiredPicker,
                                     HBox container) {
    }

    private record PendingEntry(ItemModel model, int quantity, Money cost, StorageLocation location,
                                 LocalDate acquiredOn) {
    }

    private final PieceService pieceService;
    private final ItemModelService itemModelService;
    private final CategoryService categoryService;
    private final StorageLocationService storageLocationService;
    private final SettingsService settingsService;
    private final SceneRouter sceneRouter;

    @FXML private VBox rowsBox;
    @FXML private Label errorLabel;

    private final List<RowControls> rows = new ArrayList<>();
    private final List<QuickRowControls> quickRows = new ArrayList<>();
    private final List<ComboBox<Category>> categoryCombos = new ArrayList<>();
    private final List<ComboBox<StorageLocation>> locationCombos = new ArrayList<>();
    private Category lastUsedCategory;
    private StorageLocation lastUsedLocation;

    public OpeningStockEntryController(PieceService pieceService, ItemModelService itemModelService,
                                        CategoryService categoryService,
                                        StorageLocationService storageLocationService,
                                        SettingsService settingsService, SceneRouter sceneRouter) {
        this.pieceService = pieceService;
        this.itemModelService = itemModelService;
        this.categoryService = categoryService;
        this.storageLocationService = storageLocationService;
        this.settingsService = settingsService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        resetRows();
    }

    private void resetRows() {
        rows.clear();
        quickRows.clear();
        categoryCombos.clear();
        locationCombos.clear();
        lastUsedCategory = null;
        lastUsedLocation = null;
        rowsBox.getChildren().clear();
        errorLabel.setText("");

        boolean catalogueIsEmpty = itemModelService.search(ItemModelSearchCriteria.defaultCriteria()).isEmpty();
        if (catalogueIsEmpty) {
            addNewItemRow();
        } else {
            addRow();
        }
    }

    @FXML
    private void onAddRowClicked() {
        addRow();
    }

    @FXML
    private void onAddNewItemRowClicked() {
        addNewItemRow();
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

        ComboBox<StorageLocation> locationCombo = newLocationCombo();

        DatePicker acquiredPicker = new DatePicker(LocalDate.now());
        acquiredPicker.setPrefWidth(140);

        Button removeButton = new Button("Remove");

        HBox rowBox = new HBox(8, modelCombo, quantitySpinner, costField, locationCombo, acquiredPicker, removeButton);
        rowBox.setAlignment(Pos.CENTER_LEFT);

        RowControls controls = new RowControls(modelCombo, quantitySpinner, costField, locationCombo,
                acquiredPicker, rowBox);
        removeButton.setOnAction(e -> {
            rows.remove(controls);
            locationCombos.remove(locationCombo);
            rowsBox.getChildren().remove(rowBox);
        });

        rows.add(controls);
        rowsBox.getChildren().add(rowBox);
    }

    private void addNewItemRow() {
        boolean gstEnabled = settingsService.isGstEnabled();

        TextField nameField = new TextField();
        nameField.setPromptText("New item name");
        nameField.setPrefWidth(170);

        ComboBox<Category> categoryCombo = newCategoryCombo();
        Button newCategoryButton = quickCreateButton("New category", () -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("New Category");
            dialog.setHeaderText(null);
            dialog.setContentText("Category name:");
            dialog.showAndWait().ifPresent(name -> {
                if (name.isBlank()) {
                    return;
                }
                try {
                    long id = categoryService.create(name);
                    Category created = categoryService.findById(id).orElseThrow();
                    refreshCategoryCombos(created);
                    errorLabel.setText("");
                } catch (IllegalArgumentException ex) {
                    errorLabel.setText(ex.getMessage());
                }
            });
        });

        TextField costField = new TextField();
        costField.setPromptText("Cost per piece (Rs)");
        costField.setPrefWidth(120);

        TextField salePriceField = new TextField();
        salePriceField.setPromptText("Sale price (optional)");
        salePriceField.setPrefWidth(130);

        TextField hsnField = new TextField();
        hsnField.setPromptText("HSN");
        hsnField.setPrefWidth(70);
        hsnField.setVisible(gstEnabled);
        hsnField.setManaged(gstEnabled);

        TextField gstRateField = new TextField();
        gstRateField.setPromptText("GST %");
        gstRateField.setPrefWidth(60);
        gstRateField.setVisible(gstEnabled);
        gstRateField.setManaged(gstEnabled);

        Spinner<Integer> quantitySpinner = new Spinner<>(1, 999, 1);
        quantitySpinner.setEditable(true);
        quantitySpinner.setPrefWidth(80);

        ComboBox<StorageLocation> locationCombo = newLocationCombo();
        Button newLocationButton = quickCreateButton("New location", () -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("New Location");
            dialog.setHeaderText(null);
            dialog.setContentText("Location name:");
            dialog.showAndWait().ifPresent(name -> {
                if (name.isBlank()) {
                    return;
                }
                try {
                    long id = storageLocationService.create(name);
                    StorageLocation created = storageLocationService.findById(id).orElseThrow();
                    refreshLocationCombos(created);
                    errorLabel.setText("");
                } catch (IllegalArgumentException ex) {
                    errorLabel.setText(ex.getMessage());
                }
            });
        });

        DatePicker acquiredPicker = new DatePicker(LocalDate.now());
        acquiredPicker.setPrefWidth(130);

        Button removeButton = new Button("Remove");

        HBox rowBox = new HBox(6, nameField, categoryCombo, newCategoryButton, costField, salePriceField,
                hsnField, gstRateField, quantitySpinner, locationCombo, newLocationButton, acquiredPicker,
                removeButton);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        rowBox.getStyleClass().add("quick-item-row");

        QuickRowControls controls = new QuickRowControls(nameField, categoryCombo, costField, salePriceField,
                hsnField, gstRateField, quantitySpinner, locationCombo, acquiredPicker, rowBox);
        removeButton.setOnAction(e -> {
            quickRows.remove(controls);
            categoryCombos.remove(categoryCombo);
            locationCombos.remove(locationCombo);
            rowsBox.getChildren().remove(rowBox);
        });

        quickRows.add(controls);
        rowsBox.getChildren().add(rowBox);
    }

    private ComboBox<Category> newCategoryCombo() {
        ComboBox<Category> combo = new ComboBox<>(FXCollections.observableArrayList(categoryService.listAll()));
        combo.setPrefWidth(140);
        combo.setPromptText("Category");
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Category c) {
                return c == null ? "" : c.name();
            }

            @Override
            public Category fromString(String s) {
                return null;
            }
        });
        if (lastUsedCategory != null) {
            combo.setValue(lastUsedCategory);
        }
        combo.valueProperty().addListener((obs, was, isNow) -> {
            if (isNow != null) {
                lastUsedCategory = isNow;
            }
        });
        categoryCombos.add(combo);
        return combo;
    }

    private ComboBox<StorageLocation> newLocationCombo() {
        ComboBox<StorageLocation> combo = new ComboBox<>(
                FXCollections.observableArrayList(storageLocationService.listActive()));
        combo.setPrefWidth(130);
        combo.setPromptText("Location");
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(StorageLocation l) {
                return l == null ? "" : l.name();
            }

            @Override
            public StorageLocation fromString(String s) {
                return null;
            }
        });
        if (lastUsedLocation != null) {
            combo.setValue(lastUsedLocation);
        }
        combo.valueProperty().addListener((obs, was, isNow) -> {
            if (isNow != null) {
                lastUsedLocation = isNow;
            }
        });
        locationCombos.add(combo);
        return combo;
    }

    private static Button quickCreateButton(String tooltip, Runnable action) {
        Button button = new Button("+");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
        return button;
    }

    private void refreshCategoryCombos(Category select) {
        List<Category> all = categoryService.listAll();
        for (ComboBox<Category> combo : categoryCombos) {
            Category previous = combo.getValue();
            combo.setItems(FXCollections.observableArrayList(all));
            combo.setValue(previous);
        }
        if (!categoryCombos.isEmpty()) {
            categoryCombos.get(categoryCombos.size() - 1).setValue(select);
        }
        lastUsedCategory = select;
    }

    private void refreshLocationCombos(StorageLocation select) {
        List<StorageLocation> all = storageLocationService.listActive();
        for (ComboBox<StorageLocation> combo : locationCombos) {
            StorageLocation previous = combo.getValue();
            combo.setItems(FXCollections.observableArrayList(all));
            combo.setValue(previous);
        }
        if (!locationCombos.isEmpty()) {
            locationCombos.get(locationCombos.size() - 1).setValue(select);
        }
        lastUsedLocation = select;
    }

    @FXML
    private void onSaveAllClicked() {
        List<PendingEntry> pending = new ArrayList<>();

        for (RowControls row : rows) {
            ItemModel model = row.modelCombo().getValue();
            if (model == null) {
                continue;
            }
            PendingEntry entry = toPendingEntry(model, row.quantitySpinner().getValue(), row.costField().getText(),
                    row.locationCombo().getValue(), row.acquiredPicker().getValue());
            if (entry == null) {
                return;
            }
            pending.add(entry);
        }

        for (QuickRowControls row : quickRows) {
            String name = row.nameField().getText();
            if (name == null || name.isBlank()) {
                continue;
            }
            Category category = row.categoryCombo().getValue();
            if (category == null) {
                errorLabel.setText("Pick or create a category for \"" + name.trim() + "\".");
                return;
            }

            boolean gstEnabled = settingsService.isGstEnabled();
            String hsnCode = null;
            BigDecimal gstRate = null;
            if (gstEnabled) {
                if (isBlank(row.hsnField().getText())) {
                    errorLabel.setText("Enter an HSN code for \"" + name.trim() + "\".");
                    return;
                }
                hsnCode = row.hsnField().getText().trim();
                gstRate = parseDecimalOrError(row.gstRateField().getText(), "GST rate for \"" + name.trim() + "\"");
                if (gstRate == null) {
                    return;
                }
            }

            Money salePrice = null;
            if (!isBlank(row.salePriceField().getText())) {
                try {
                    salePrice = Money.ofRupees(row.salePriceField().getText().trim());
                } catch (NumberFormatException e) {
                    errorLabel.setText("Invalid sale price for \"" + name.trim() + "\".");
                    return;
                }
            }

            ItemModel created;
            try {
                created = quickCreateItemModel(name.trim(), category, hsnCode, gstRate, salePrice);
            } catch (IllegalArgumentException e) {
                errorLabel.setText(e.getMessage());
                return;
            }

            PendingEntry entry = toPendingEntry(created, row.quantitySpinner().getValue(), row.costField().getText(),
                    row.locationCombo().getValue(), row.acquiredPicker().getValue());
            if (entry == null) {
                return;
            }
            pending.add(entry);
        }

        if (pending.isEmpty()) {
            errorLabel.setText("Add at least one row with a model or a new item name.");
            return;
        }

        long totalPaisa = 0;
        int totalPieces = 0;
        for (PendingEntry entry : pending) {
            totalPieces += entry.quantity();
            totalPaisa += entry.cost().paisa() * entry.quantity();
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

    /** Shared tail of both row kinds once a real {@link ItemModel} is in hand - returns
     *  {@code null} (having already set {@link #errorLabel}) on a validation failure. */
    private PendingEntry toPendingEntry(ItemModel model, int quantity, String costText, StorageLocation location,
                                         LocalDate acquiredOn) {
        if (isBlank(costText)) {
            errorLabel.setText("Enter a per-piece cost for " + model.modelName() + ".");
            return null;
        }
        Money cost;
        try {
            cost = Money.ofRupees(costText.trim());
        } catch (NumberFormatException e) {
            errorLabel.setText("Invalid cost for " + model.modelName() + ".");
            return null;
        }
        return new PendingEntry(model, quantity, cost, location, acquiredOn == null ? LocalDate.now() : acquiredOn);
    }

    /** M10: turns a free-typed name into a real, catalogued {@link ItemModel} - a model code
     *  is generated from the name (and de-duplicated against the catalogue) since asking the
     *  owner to invent a short code for every quick-entered item would defeat the point of a
     *  free-entry row. GST sentinel handling for a disabled shop happens inside
     *  {@link ItemModelService#create}, same as the full editor. */
    private ItemModel quickCreateItemModel(String name, Category category, String hsnCode, BigDecimal gstRate,
                                            Money salePrice) {
        String code = generateModelCode(name);
        ItemModel draft = new ItemModel(0, code, name, category.id(), hsnCode, gstRate,
                null, null, null, null, null, null, salePrice, true, null);
        long id = itemModelService.create(draft);
        return itemModelService.findById(id).orElseThrow();
    }

    private String generateModelCode(String name) {
        String base = name.toUpperCase().replaceAll("[^A-Z0-9]+", "");
        if (base.isBlank()) {
            base = "ITEM";
        }
        if (base.length() > 10) {
            base = base.substring(0, 10);
        }
        for (int suffix = 1; suffix <= 9999; suffix++) {
            String candidate = base + "-" + suffix;
            if (!itemModelService.modelCodeExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique model code for \"" + name + "\".");
    }

    private BigDecimal parseDecimalOrError(String text, String label) {
        if (isBlank(text)) {
            errorLabel.setText(label.substring(0, 1).toUpperCase() + label.substring(1) + " is required.");
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            errorLabel.setText(label.substring(0, 1).toUpperCase() + label.substring(1) + " must be a number.");
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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

}
