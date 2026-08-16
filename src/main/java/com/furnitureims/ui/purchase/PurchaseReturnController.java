package com.furnitureims.ui.purchase;

import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.PurchaseBill;
import com.furnitureims.domain.PurchaseLine;
import com.furnitureims.domain.PurchaseReturn;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.PurchaseBillService;
import com.furnitureims.service.PurchaseReturnService;
import com.furnitureims.ui.SceneRouter;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Purchase return (FR-PUR-07; docs/03-screens.md 5.4): pick which still-IN_STOCK pieces
 *  from a received bill go back to the supplier. */
@Component
public class PurchaseReturnController {

    private final PurchaseBillService purchaseBillService;
    private final PurchaseReturnService purchaseReturnService;
    private final PieceService pieceService;
    private final ItemModelService itemModelService;
    private final SceneRouter sceneRouter;

    @FXML private Label titleLabel;
    @FXML private VBox pieceCheckboxesBox;
    @FXML private TextField reasonField;
    @FXML private DatePicker returnDatePicker;
    @FXML private Label errorLabel;

    private final Map<CheckBox, Long> checkboxToPieceId = new LinkedHashMap<>();
    private long billId;

    public PurchaseReturnController(PurchaseBillService purchaseBillService,
                                     PurchaseReturnService purchaseReturnService, PieceService pieceService,
                                     ItemModelService itemModelService, SceneRouter sceneRouter) {
        this.purchaseBillService = purchaseBillService;
        this.purchaseReturnService = purchaseReturnService;
        this.pieceService = pieceService;
        this.itemModelService = itemModelService;
        this.sceneRouter = sceneRouter;
    }

    public void openFor(long billId) {
        this.billId = billId;
    }

    @FXML
    private void initialize() {
        errorLabel.setText("");
        returnDatePicker.setValue(LocalDate.now());
        reload();
    }

    private void reload() {
        PurchaseBill bill = purchaseBillService.findById(billId)
                .orElseThrow(() -> new IllegalStateException("Purchase bill not found: " + billId));
        titleLabel.setText("Return Pieces - Bill " + bill.supplierBillNo());

        pieceCheckboxesBox.getChildren().clear();
        checkboxToPieceId.clear();

        List<Piece> inStockPieces = new ArrayList<>();
        for (PurchaseLine line : purchaseBillService.linesFor(billId)) {
            for (Piece piece : pieceService.findByPurchaseLineId(line.id())) {
                if (piece.state() == Piece.State.IN_STOCK) {
                    inStockPieces.add(piece);
                }
            }
        }

        if (inStockPieces.isEmpty()) {
            pieceCheckboxesBox.getChildren().add(
                    new Label("No pieces from this bill are currently in stock to return."));
            return;
        }

        for (Piece piece : inStockPieces) {
            String modelName = itemModelService.findById(piece.itemModelId())
                    .map(ItemModel::modelName).orElse("?");
            CheckBox checkBox = new CheckBox(piece.tag() + " - " + modelName + " - "
                    + piece.landedCost().toDisplayString());
            pieceCheckboxesBox.getChildren().add(checkBox);
            checkboxToPieceId.put(checkBox, piece.id());
        }
    }

    @FXML
    private void onSubmitClicked() {
        try {
            List<Long> selected = checkboxToPieceId.entrySet().stream()
                    .filter(e -> e.getKey().isSelected())
                    .map(Map.Entry::getValue)
                    .toList();
            if (selected.isEmpty()) {
                errorLabel.setText("Select at least one piece to return.");
                return;
            }
            LocalDate returnDate = returnDatePicker.getValue() == null ? LocalDate.now() : returnDatePicker.getValue();
            long returnId = purchaseReturnService.createReturn(billId, selected, reasonField.getText(), returnDate);

            PurchaseReturn purchaseReturn = purchaseReturnService.returnsFor(billId).stream()
                    .filter(r -> r.id() == returnId).findFirst().orElseThrow();

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Return Recorded");
            info.setHeaderText("Debit note " + purchaseReturn.debitNoteNo());
            info.setContentText("Credited " + purchaseReturn.totalAmount().toDisplayString()
                    + " for " + selected.size() + " piece(s).");
            info.showAndWait();

            sceneRouter.show("/fxml/purchase/purchase-bill-list.fxml");
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/purchase/purchase-bill-list.fxml");
    }
}
