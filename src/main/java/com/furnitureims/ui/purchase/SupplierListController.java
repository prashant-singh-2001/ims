package com.furnitureims.ui.purchase;

import com.furnitureims.domain.Supplier;
import com.furnitureims.service.SettingsService;
import com.furnitureims.service.SupplierService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import org.springframework.stereotype.Component;

import java.util.List;

/** Suppliers list (FR-PUR-01/09; docs/03-screens.md 5.1). */
@Component
public class SupplierListController {

    private final SupplierService supplierService;
    private final SupplierEditorController supplierEditorController;
    private final SettingsService settingsService;
    private final SceneRouter sceneRouter;

    @FXML private TableView<SupplierRow> table;
    @FXML private TableColumn<SupplierRow, String> nameColumn;
    @FXML private TableColumn<SupplierRow, String> gstinColumn;
    @FXML private TableColumn<SupplierRow, String> stateColumn;
    @FXML private TableColumn<SupplierRow, String> phoneColumn;
    @FXML private TableColumn<SupplierRow, String> duesColumn;
    @FXML private TableColumn<SupplierRow, String> statusColumn;
    @FXML private TableColumn<SupplierRow, Void> actionsColumn;

    public SupplierListController(SupplierService supplierService,
                                   SupplierEditorController supplierEditorController,
                                   SettingsService settingsService, SceneRouter sceneRouter) {
        this.supplierService = supplierService;
        this.supplierEditorController = supplierEditorController;
        this.settingsService = settingsService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        gstinColumn.setCellValueFactory(new PropertyValueFactory<>("gstin"));
        gstinColumn.setVisible(settingsService.isGstEnabled());
        stateColumn.setCellValueFactory(new PropertyValueFactory<>("stateName"));
        phoneColumn.setCellValueFactory(new PropertyValueFactory<>("phone"));
        duesColumn.setCellValueFactory(new PropertyValueFactory<>("dues"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("statusLabel"));
        actionsColumn.setCellFactory(col -> actionsCell());
        reload();
    }

    private TableCell<SupplierRow, Void> actionsCell() {
        return new TableCell<>() {
            private final Button editButton = new Button("Edit");
            private final Button toggleButton = new Button();

            {
                editButton.setOnAction(e -> onEdit(rowAt()));
                toggleButton.setOnAction(e -> onToggleActive(rowAt()));
            }

            private SupplierRow rowAt() {
                return getTableView().getItems().get(getIndex());
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                toggleButton.setText(rowAt().getSupplier().active() ? "Deactivate" : "Reactivate");
                setGraphic(new HBox(6, editButton, toggleButton));
            }
        };
    }

    private void onEdit(SupplierRow row) {
        supplierEditorController.openForEdit(row.getSupplier().id());
        sceneRouter.navigate(Route.SUPPLIER_EDITOR);
    }

    private void onToggleActive(SupplierRow row) {
        supplierService.setActive(row.getSupplier().id(), !row.getSupplier().active());
        reload();
    }

    @FXML
    private void onNewClicked() {
        supplierEditorController.openForNew();
        sceneRouter.navigate(Route.SUPPLIER_EDITOR);
    }

    private void reload() {
        List<Supplier> suppliers = supplierService.listAll();
        List<SupplierRow> rows = suppliers.stream()
                .map(s -> new SupplierRow(s, supplierService.dues(s.id())))
                .toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

}
