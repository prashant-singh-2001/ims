package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.Category;
import com.furnitureims.domain.StorageLocation;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.SceneRouter;
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

    public CategoriesLocationsController(CategoryService categoryService,
                                          StorageLocationService storageLocationService,
                                          SceneRouter sceneRouter) {
        this.categoryService = categoryService;
        this.storageLocationService = storageLocationService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
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

        reloadCategories();
        reloadLocations();
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

    private Optional<String> promptForName(String title, String currentName) {
        TextInputDialog dialog = new TextInputDialog(currentName);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("New name:");
        return dialog.showAndWait();
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/shell/dashboard.fxml");
    }
}
