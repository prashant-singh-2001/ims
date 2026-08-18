package com.furnitureims.ui.catalogue;

import com.furnitureims.domain.Category;
import com.furnitureims.repository.ItemModelSearchCriteria;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.SettingsService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.util.List;

/** Item models list (FR-ITEM-04/05; docs/03-screens.md 4.1). */
@Component
public class ItemModelListController {

    private final ItemModelService itemModelService;
    private final CategoryService categoryService;
    private final ItemModelEditorController itemModelEditorController;
    private final SettingsService settingsService;
    private final SceneRouter sceneRouter;

    @FXML private TextField searchField;
    @FXML private ComboBox<Category> categoryFilterCombo;
    @FXML private CheckBox activeOnlyCheck;

    @FXML private TableView<ItemModelRow> table;
    @FXML private TableColumn<ItemModelRow, String> codeColumn;
    @FXML private TableColumn<ItemModelRow, String> nameColumn;
    @FXML private TableColumn<ItemModelRow, String> categoryColumn;
    @FXML private TableColumn<ItemModelRow, String> hsnColumn;
    @FXML private TableColumn<ItemModelRow, String> gstColumn;
    @FXML private TableColumn<ItemModelRow, String> priceColumn;
    @FXML private TableColumn<ItemModelRow, Long> stockColumn;
    @FXML private TableColumn<ItemModelRow, String> statusColumn;
    @FXML private TableColumn<ItemModelRow, Void> actionsColumn;

    public ItemModelListController(ItemModelService itemModelService, CategoryService categoryService,
                                    ItemModelEditorController itemModelEditorController,
                                    SettingsService settingsService, SceneRouter sceneRouter) {
        this.itemModelService = itemModelService;
        this.categoryService = categoryService;
        this.itemModelEditorController = itemModelEditorController;
        this.settingsService = settingsService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        codeColumn.setCellValueFactory(new PropertyValueFactory<>("modelCode"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("modelName"));
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("categoryName"));
        hsnColumn.setCellValueFactory(new PropertyValueFactory<>("hsnCode"));
        gstColumn.setCellValueFactory(new PropertyValueFactory<>("gstRate"));
        boolean gstEnabled = settingsService.isGstEnabled();
        hsnColumn.setVisible(gstEnabled);
        gstColumn.setVisible(gstEnabled);
        priceColumn.setCellValueFactory(new PropertyValueFactory<>("defaultPrice"));
        stockColumn.setCellValueFactory(new PropertyValueFactory<>("inStockCount"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("activeLabel"));
        actionsColumn.setCellFactory(col -> actionsCell());

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
        activeOnlyCheck.setSelected(true);

        reload();
    }

    private TableCell<ItemModelRow, Void> actionsCell() {
        return new TableCell<>() {
            private final Button editButton = new Button("Edit");
            private final Button toggleButton = new Button();
            private final Button viewPiecesButton = new Button("View Pieces");

            {
                editButton.setOnAction(e -> onEditClicked(rowAt()));
                toggleButton.setOnAction(e -> onToggleActiveClicked(rowAt()));
                viewPiecesButton.setOnAction(e -> sceneRouter.navigate(Route.PIECE_REGISTER));
            }

            private ItemModelRow rowAt() {
                return getTableView().getItems().get(getIndex());
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                toggleButton.setText(rowAt().getSummary().model().active() ? "Discontinue" : "Reactivate");
                setGraphic(new HBox(6, editButton, toggleButton, viewPiecesButton));
            }
        };
    }

    private void onEditClicked(ItemModelRow row) {
        itemModelEditorController.openForEdit(row.getId());
        sceneRouter.navigate(Route.ITEM_MODEL_EDITOR);
    }

    private void onToggleActiveClicked(ItemModelRow row) {
        itemModelService.setActive(row.getId(), !row.getSummary().model().active());
        reload();
    }

    @FXML
    private void onNewClicked() {
        itemModelEditorController.openForNew();
        sceneRouter.navigate(Route.ITEM_MODEL_EDITOR);
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        Long categoryId = categoryFilterCombo.getValue() == null ? null : categoryFilterCombo.getValue().id();
        ItemModelSearchCriteria criteria = new ItemModelSearchCriteria(
                searchField.getText(), categoryId, activeOnlyCheck.isSelected(),
                null, null, null, null, null, null);
        List<ItemModelRow> rows = itemModelService.search(criteria).stream().map(ItemModelRow::new).toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onCategoriesLocationsClicked() {
        sceneRouter.navigate(Route.CATEGORIES_LOCATIONS);
    }

}
