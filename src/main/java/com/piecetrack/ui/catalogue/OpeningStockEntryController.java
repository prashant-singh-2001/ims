package com.piecetrack.ui.catalogue;

import com.piecetrack.domain.Category;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.StorageLocation;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ItemModelSearchCriteria;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.SettingsService;
import com.piecetrack.service.StorageLocationService;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
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

        Spinner<Integer> quantitySpinner = new Spinner<>(0, 999, 0);
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

        Spinner<Integer> quantitySpinner = new Spinner<>(0, 999, 0);
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
            // false: this row picks an already-catalogued model, so a quantity of 0
            // contributes nothing at all - almost certainly a forgotten field, not intent.
            PendingEntry entry = toPendingEntry(model, row.quantitySpinner().getValue(), row.costField().getText(),
                    row.locationCombo().getValue(), row.acquiredPicker().getValue(), false);
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

            // true: this row's model was just created above (or already existed for this
            // save, if re-added) - a quantity of 0 is a deliberate "add it to the catalogue,
            // stock arrives later" entry, not a mistake.
            PendingEntry entry = toPendingEntry(created, row.quantitySpinner().getValue(), row.costField().getText(),
                    row.locationCombo().getValue(), row.acquiredPicker().getValue(), true);
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
        int catalogueOnlyCount = 0;
        for (PendingEntry entry : pending) {
            if (entry.quantity() == 0) {
                // A new-item row saved at quantity 0: the model was already created above,
                // there is nothing further to do for it here - counted separately so the
                // confirmation and the closing summary don't read as if nothing happened.
                catalogueOnlyCount++;
                continue;
            }
            totalPieces += entry.quantity();
            totalPaisa += entry.cost().paisa() * entry.quantity();
        }

        Money totalValue = Money.ofPaisa(totalPaisa);
        StringBuilder confirmText = new StringBuilder();
        if (totalPieces > 0) {
            confirmText.append("This will create ").append(totalPieces).append(" piece")
                    .append(totalPieces == 1 ? "" : "s").append(" worth ").append(totalValue.toDisplayString())
                    .append(" total.");
        }
        if (catalogueOnlyCount > 0) {
            if (!confirmText.isEmpty()) {
                confirmText.append(' ');
            }
            confirmText.append(catalogueOnlyCount).append(" item").append(catalogueOnlyCount == 1 ? "" : "s")
                    .append(" will be added to the catalogue with no stock yet.");
        }
        confirmText.append(" Continue?");

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Opening Stock");
        confirm.setHeaderText(null);
        confirm.setContentText(confirmText.toString());
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        List<String> generatedTags = new ArrayList<>();
        for (PendingEntry entry : pending) {
            if (entry.quantity() == 0) {
                // The item model was already created via quickCreateItemModel above; opening
                // stock's own guard refuses a quantity below 1, so this must be skipped here
                // rather than passed through.
                continue;
            }
            Long locationId = entry.location() == null ? null : entry.location().id();
            List<Piece> created = pieceService.createOpeningStock(
                    entry.model().id(), entry.quantity(), entry.cost(), locationId, entry.acquiredOn());
            for (Piece piece : created) {
                generatedTags.add(piece.tag());
            }
        }

        errorLabel.setText("");
        if (!generatedTags.isEmpty()) {
            showGeneratedTags(generatedTags);
        } else {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Catalogue Updated");
            info.setHeaderText(null);
            info.setContentText(catalogueOnlyCount + " item" + (catalogueOnlyCount == 1 ? "" : "s")
                    + " added to the catalogue. No pieces were created - add stock later from this screen "
                    + "or a purchase bill.");
            info.showAndWait();
        }
        resetRows();
    }

    /** Shared tail of both row kinds once a real {@link ItemModel} is in hand - returns
     *  {@code null} (having already set {@link #errorLabel}) on a validation failure.
     *  @param zeroQuantityAllowed false for the existing-model row, where a 0 contributes
     *         nothing and is almost certainly a forgotten field; true for the new-item row,
     *         where 0 is a deliberate "catalogue it now, stock arrives later" entry - see the
     *         two call sites. A 0 entry needs no cost, since no piece is being priced. */
    private PendingEntry toPendingEntry(ItemModel model, int quantity, String costText, StorageLocation location,
                                         LocalDate acquiredOn, boolean zeroQuantityAllowed) {
        LocalDate resolvedAcquiredOn = acquiredOn == null ? LocalDate.now() : acquiredOn;
        if (quantity == 0) {
            if (!zeroQuantityAllowed) {
                errorLabel.setText(model.modelName() + " is already in the catalogue - a quantity of 0 "
                        + "adds no stock. Remove the row or enter a quantity.");
                return null;
            }
            return new PendingEntry(model, 0, Money.ofPaisa(0), location, resolvedAcquiredOn);
        }
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
        return new PendingEntry(model, quantity, cost, location, resolvedAcquiredOn);
    }

    /** M10: turns a free-typed name into a real, catalogued {@link ItemModel} - a model code
     *  is generated from the name (and de-duplicated against the catalogue) since asking the
     *  owner to invent a short code for every quick-entered item would defeat the point of a
     *  free-entry row. GST sentinel handling for a disabled shop happens inside
     *  {@link ItemModelService#create}, same as the full editor. */
    private ItemModel quickCreateItemModel(String name, Category category, String hsnCode, BigDecimal gstRate,
                                            Money salePrice) {
        String code = generateModelCode(name);
        ItemModel draft = new ItemModel(0, code, name, category.id(), hsnCode, gstRate, salePrice, true, null);
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
        alert.setHeaderText(tags.size() + " piece(s) created. Write these tags onto the physical items:");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

}
