package com.piecetrack.ui.catalogue;

import com.piecetrack.domain.AttributeDefinition;
import com.piecetrack.domain.Category;
import com.piecetrack.domain.StorageLocation;
import com.piecetrack.service.AttributeDefinitionService;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.StorageLocationService;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Manages the two lookup lists used throughout the catalogue and piece register:
 *  categories (FR-ITEM-01) and storage locations (FR-PIECE-06). A lightweight stand-in
 *  for the "Lists" section of the full Settings screen (docs/03-screens.md section 10),
 *  which is later scope. */
@Component
public class CategoriesLocationsController {

    private final CategoryService categoryService;
    private final StorageLocationService storageLocationService;
    private final AttributeDefinitionService attributeDefinitionService;
    private final SceneRouter sceneRouter;

    @FXML private TableView<CategoryRow> categoryTable;
    @FXML private TableColumn<CategoryRow, String> categoryNameColumn;
    @FXML private TableColumn<CategoryRow, Boolean> categoryActiveColumn;
    @FXML private TableColumn<CategoryRow, Void> categoryActionsColumn;
    @FXML private TextField newCategoryField;
    @FXML private Label categoryErrorLabel;

    @FXML private TableView<StorageLocationRow> locationTable;
    @FXML private TableColumn<StorageLocationRow, String> locationNameColumn;
    @FXML private TableColumn<StorageLocationRow, Boolean> locationActiveColumn;
    @FXML private TableColumn<StorageLocationRow, Void> locationActionsColumn;
    @FXML private TextField newLocationField;
    @FXML private Label locationErrorLabel;

    @FXML private TableView<AttributeDefinitionRow> attributeTable;
    @FXML private TableColumn<AttributeDefinitionRow, String> attributeNameColumn;
    @FXML private TableColumn<AttributeDefinitionRow, Boolean> attributeActiveColumn;
    @FXML private TableColumn<AttributeDefinitionRow, Void> attributeActionsColumn;
    @FXML private TextField newAttributeField;
    @FXML private Label attributeErrorLabel;

    public CategoriesLocationsController(CategoryService categoryService,
                                          StorageLocationService storageLocationService,
                                          AttributeDefinitionService attributeDefinitionService,
                                          SceneRouter sceneRouter) {
        this.categoryService = categoryService;
        this.storageLocationService = storageLocationService;
        this.attributeDefinitionService = attributeDefinitionService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        locationTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        categoryActiveColumn.setCellValueFactory(new PropertyValueFactory<>("active"));
        categoryActiveColumn.setCellFactory(CheckBoxTableCell.forTableColumn(categoryActiveColumn));
        categoryActionsColumn.setCellFactory(col -> renameButtonCell(this::onRenameCategory));
        categoryTable.setEditable(true);

        locationNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        locationActiveColumn.setCellValueFactory(new PropertyValueFactory<>("active"));
        locationActiveColumn.setCellFactory(CheckBoxTableCell.forTableColumn(locationActiveColumn));
        locationActionsColumn.setCellFactory(col -> renameButtonCell(this::onRenameLocation));
        locationTable.setEditable(true);

        attributeNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        attributeActiveColumn.setCellValueFactory(new PropertyValueFactory<>("active"));
        attributeActiveColumn.setCellFactory(CheckBoxTableCell.forTableColumn(attributeActiveColumn));
        attributeActionsColumn.setCellFactory(col -> renameButtonCell(this::onRenameAttribute));
        attributeTable.setEditable(true);

        reloadCategories();
        reloadLocations();
        reloadAttributes();
    }

    private <T> TableCell<T, Void> renameButtonCell(java.util.function.Consumer<T> onRename) {
        return new TableCell<>() {
            private final Button renameButton = new Button("Rename");
            {
                renameButton.setOnAction(e -> onRename.accept(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : renameButton);
            }
        };
    }

    private void reloadCategories() {
        ObservableList<CategoryRow> rows = FXCollections.observableArrayList();
        for (Category category : categoryService.listAll()) {
            CategoryRow row = new CategoryRow(category);
            row.activeProperty().addListener((obs, was, isNow) -> categoryService.setActive(row.getId(), isNow));
            rows.add(row);
        }
        categoryTable.setItems(rows);
    }

    private void reloadLocations() {
        ObservableList<StorageLocationRow> rows = FXCollections.observableArrayList();
        for (StorageLocation location : storageLocationService.listAll()) {
            StorageLocationRow row = new StorageLocationRow(location);
            row.activeProperty().addListener((obs, was, isNow) ->
                    storageLocationService.setActive(row.getId(), isNow));
            rows.add(row);
        }
        locationTable.setItems(rows);
    }

    private void reloadAttributes() {
        ObservableList<AttributeDefinitionRow> rows = FXCollections.observableArrayList();
        for (AttributeDefinition definition : attributeDefinitionService.listAll()) {
            AttributeDefinitionRow row = new AttributeDefinitionRow(definition);
            row.activeProperty().addListener((obs, was, isNow) ->
                    attributeDefinitionService.setActive(row.getId(), isNow));
            rows.add(row);
        }
        attributeTable.setItems(rows);
    }

    @FXML
    private void onAddCategoryClicked() {
        try {
            categoryService.create(newCategoryField.getText());
            newCategoryField.clear();
            categoryErrorLabel.setText("");
            reloadCategories();
        } catch (IllegalArgumentException e) {
            categoryErrorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onAddLocationClicked() {
        try {
            storageLocationService.create(newLocationField.getText());
            newLocationField.clear();
            locationErrorLabel.setText("");
            reloadLocations();
        } catch (IllegalArgumentException e) {
            locationErrorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onAddAttributeClicked() {
        try {
            attributeDefinitionService.create(newAttributeField.getText());
            newAttributeField.clear();
            attributeErrorLabel.setText("");
            reloadAttributes();
        } catch (IllegalArgumentException e) {
            attributeErrorLabel.setText(e.getMessage());
        }
    }

    private void onRenameCategory(CategoryRow row) {
        Optional<String> result = promptForName("Rename Category", row.getName());
        result.ifPresent(newName -> {
            try {
                categoryService.rename(row.getId(), newName);
                categoryErrorLabel.setText("");
                reloadCategories();
            } catch (IllegalArgumentException e) {
                categoryErrorLabel.setText(e.getMessage());
            }
        });
    }

    private void onRenameLocation(StorageLocationRow row) {
        Optional<String> result = promptForName("Rename Location", row.getName());
        result.ifPresent(newName -> {
            try {
                storageLocationService.rename(row.getId(), newName);
                locationErrorLabel.setText("");
                reloadLocations();
            } catch (IllegalArgumentException e) {
                locationErrorLabel.setText(e.getMessage());
            }
        });
    }

    private void onRenameAttribute(AttributeDefinitionRow row) {
        Optional<String> result = promptForName("Rename Attribute", row.getName());
        result.ifPresent(newName -> {
            try {
                attributeDefinitionService.rename(row.getId(), newName);
                attributeErrorLabel.setText("");
                reloadAttributes();
            } catch (IllegalArgumentException e) {
                attributeErrorLabel.setText(e.getMessage());
            }
        });
    }

    private Optional<String> promptForName(String title, String currentName) {
        TextInputDialog dialog = new TextInputDialog(currentName);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("New name:");
        return dialog.showAndWait();
    }

}
