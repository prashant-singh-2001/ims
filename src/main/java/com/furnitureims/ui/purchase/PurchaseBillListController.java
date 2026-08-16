package com.furnitureims.ui.purchase;

import com.furnitureims.domain.PurchaseBill;
import com.furnitureims.domain.PurchaseLine;
import com.furnitureims.repository.PurchaseBillListRow;
import com.furnitureims.repository.PurchaseBillSearchCriteria;
import com.furnitureims.service.PurchaseBillService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Purchase bills list (docs/03-screens.md 5.3): drives receipt, reversal and return. */
@Component
public class PurchaseBillListController {

    private final PurchaseBillService purchaseBillService;
    private final PurchaseBillEntryController purchaseBillEntryController;
    private final PurchaseReturnController purchaseReturnController;
    private final SceneRouter sceneRouter;

    @FXML private TableView<PurchaseBillRow> table;
    @FXML private TableColumn<PurchaseBillRow, String> billNoColumn;
    @FXML private TableColumn<PurchaseBillRow, String> supplierColumn;
    @FXML private TableColumn<PurchaseBillRow, String> dateColumn;
    @FXML private TableColumn<PurchaseBillRow, String> totalColumn;
    @FXML private TableColumn<PurchaseBillRow, String> balanceColumn;
    @FXML private TableColumn<PurchaseBillRow, String> statusColumn;
    @FXML private TableColumn<PurchaseBillRow, Void> actionsColumn;
    @FXML private Label errorLabel;

    public PurchaseBillListController(PurchaseBillService purchaseBillService,
                                       PurchaseBillEntryController purchaseBillEntryController,
                                       PurchaseReturnController purchaseReturnController,
                                       SceneRouter sceneRouter) {
        this.purchaseBillService = purchaseBillService;
        this.purchaseBillEntryController = purchaseBillEntryController;
        this.purchaseReturnController = purchaseReturnController;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        billNoColumn.setCellValueFactory(new PropertyValueFactory<>("billNo"));
        supplierColumn.setCellValueFactory(new PropertyValueFactory<>("supplierName"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("billDate"));
        totalColumn.setCellValueFactory(new PropertyValueFactory<>("grandTotal"));
        balanceColumn.setCellValueFactory(new PropertyValueFactory<>("balance"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        actionsColumn.setCellFactory(col -> actionsCell());
        errorLabel.setText("");
        reload();
    }

    private TableCell<PurchaseBillRow, Void> actionsCell() {
        return new TableCell<>() {
            private final Button editButton = new Button("Edit");
            private final Button confirmButton = new Button("Confirm Receipt");
            private final Button reverseButton = new Button("Reverse Receipt");
            private final Button returnButton = new Button("Return Pieces");
            private final HBox box = new HBox(6);

            {
                editButton.setOnAction(e -> onEdit(rowAt()));
                confirmButton.setOnAction(e -> onConfirm(rowAt()));
                reverseButton.setOnAction(e -> onReverse(rowAt()));
                returnButton.setOnAction(e -> onReturn(rowAt()));
            }

            private PurchaseBillRow rowAt() {
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
                PurchaseBill.Status status = rowAt().getRow().bill().status();
                if (status == PurchaseBill.Status.DRAFT) {
                    box.getChildren().addAll(editButton, confirmButton);
                } else if (status == PurchaseBill.Status.RECEIVED) {
                    box.getChildren().addAll(reverseButton, returnButton);
                }
                setGraphic(box);
            }
        };
    }

    private void onEdit(PurchaseBillRow row) {
        purchaseBillEntryController.openForEdit(row.getId());
        sceneRouter.show("/fxml/purchase/purchase-bill-entry.fxml");
    }

    private void onConfirm(PurchaseBillRow row) {
        int totalPieces = purchaseBillService.linesFor(row.getId()).stream()
                .mapToInt(PurchaseLine::quantity).sum();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Receipt");
        confirm.setHeaderText(null);
        confirm.setContentText("This will create " + totalPieces + " piece(s). Continue?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                purchaseBillService.confirmReceipt(row.getId());
                errorLabel.setText("");
                reload();
            } catch (IllegalStateException e) {
                errorLabel.setText(e.getMessage());
            }
        }
    }

    private void onReverse(PurchaseBillRow row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reverse Receipt");
        confirm.setHeaderText(null);
        confirm.setContentText("This puts the bill back to draft and removes the pieces it created, "
                + "if none have been sold, moved or returned. Continue?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                purchaseBillService.reverseReceipt(row.getId());
                errorLabel.setText("");
                reload();
            } catch (IllegalStateException e) {
                errorLabel.setText(e.getMessage());
            }
        }
    }

    private void onReturn(PurchaseBillRow row) {
        purchaseReturnController.openFor(row.getId());
        sceneRouter.show("/fxml/purchase/purchase-return.fxml");
    }

    @FXML
    private void onNewClicked() {
        purchaseBillEntryController.openForNew();
        sceneRouter.show("/fxml/purchase/purchase-bill-entry.fxml");
    }

    private void reload() {
        List<PurchaseBillListRow> results = purchaseBillService.search(PurchaseBillSearchCriteria.empty());
        List<PurchaseBillRow> rows = results.stream()
                .map(r -> new PurchaseBillRow(r, purchaseBillService.balance(r.bill())))
                .toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onSuppliersClicked() {
        sceneRouter.show("/fxml/purchase/supplier-list.fxml");
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/shell/dashboard-placeholder.fxml");
    }
}
