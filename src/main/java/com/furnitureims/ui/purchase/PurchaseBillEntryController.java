package com.furnitureims.ui.purchase;

import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.PurchaseBill;
import com.furnitureims.domain.PurchaseLine;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ItemModelSearchCriteria;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PurchaseBillService;
import com.furnitureims.service.SupplierService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Purchase bill entry (FR-PUR-03..06; docs/03-screens.md 5.2): header, dynamic line-item
 * rows, charges, and a live totals preview backed by {@link PurchaseBillService#preview}
 * so what the owner sees before saving matches what actually gets saved. Reopening an
 * existing DRAFT (via {@link #openForEdit}) reconstructs the rows from its saved lines;
 * a fresh bill starts with one blank row.
 */
@Component
public class PurchaseBillEntryController {

    private record LineRowControls(ComboBox<ItemModel> modelCombo, Spinner<Integer> quantitySpinner,
                                    TextField rateField, TextField discountField, TextField gstRateField,
                                    HBox container) {
    }

    private final PurchaseBillService purchaseBillService;
    private final SupplierService supplierService;
    private final ItemModelService itemModelService;
    private final SceneRouter sceneRouter;

    @FXML private Label titleLabel;
    @FXML private ComboBox<Supplier> supplierCombo;
    @FXML private TextField billNoField;
    @FXML private DatePicker billDatePicker;
    @FXML private DatePicker receivedDatePicker;
    @FXML private VBox rowsBox;
    @FXML private TextField freightField;
    @FXML private TextField loadingChargesField;
    @FXML private TextField otherChargesField;
    @FXML private TextArea notesArea;
    @FXML private Label taxableValueLabel;
    @FXML private Label cgstLabel;
    @FXML private Label sgstLabel;
    @FXML private Label igstLabel;
    @FXML private Label chargesLabel;
    @FXML private Label grandTotalLabel;
    @FXML private Label errorLabel;

    private final List<LineRowControls> rows = new ArrayList<>();
    private Long editingId;
    private boolean pendingIsNew = true;

    public PurchaseBillEntryController(PurchaseBillService purchaseBillService, SupplierService supplierService,
                                        ItemModelService itemModelService, SceneRouter sceneRouter) {
        this.purchaseBillService = purchaseBillService;
        this.supplierService = supplierService;
        this.itemModelService = itemModelService;
        this.sceneRouter = sceneRouter;
    }

    public void openForNew() {
        editingId = null;
        pendingIsNew = true;
    }

    public void openForEdit(long billId) {
        editingId = billId;
        pendingIsNew = false;
    }

    @FXML
    private void initialize() {
        supplierCombo.setItems(FXCollections.observableArrayList(supplierService.listActive()));
        supplierCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Supplier s) {
                return s == null ? "" : s.name();
            }

            @Override
            public Supplier fromString(String string) {
                return null;
            }
        });
        errorLabel.setText("");

        if (pendingIsNew) {
            resetForNew();
        } else {
            loadForEdit(editingId);
        }
    }

    private void resetForNew() {
        titleLabel.setText("New Purchase Bill");
        supplierCombo.setValue(null);
        billNoField.clear();
        billDatePicker.setValue(LocalDate.now());
        receivedDatePicker.setValue(LocalDate.now());
        freightField.clear();
        loadingChargesField.clear();
        otherChargesField.clear();
        notesArea.clear();
        rows.clear();
        rowsBox.getChildren().clear();
        clearTotalsLabels();
        addRow();
    }

    private void loadForEdit(long billId) {
        PurchaseBill bill = purchaseBillService.findById(billId)
                .orElseThrow(() -> new IllegalStateException("Purchase bill not found: " + billId));
        if (bill.status() != PurchaseBill.Status.DRAFT) {
            errorLabel.setText("Only a draft bill can be edited.");
        }
        titleLabel.setText("Edit Purchase Bill (Draft) - " + bill.supplierBillNo());
        supplierService.findById(bill.supplierId()).ifPresent(supplierCombo::setValue);
        billNoField.setText(bill.supplierBillNo());
        billDatePicker.setValue(bill.billDate());
        receivedDatePicker.setValue(bill.receivedDate());
        freightField.setText(bill.freight().rupees().toPlainString());
        loadingChargesField.setText(bill.loadingCharges().rupees().toPlainString());
        otherChargesField.setText(bill.otherCharges().rupees().toPlainString());
        notesArea.setText(bill.notes());

        rows.clear();
        rowsBox.getChildren().clear();
        for (PurchaseLine line : purchaseBillService.linesFor(billId)) {
            addRow();
            LineRowControls row = rows.get(rows.size() - 1);
            itemModelService.findById(line.itemModelId()).ifPresent(row.modelCombo()::setValue);
            row.quantitySpinner().getValueFactory().setValue(line.quantity());
            row.rateField().setText(line.rate().rupees().toPlainString());
            row.discountField().setText(line.discountAmount().rupees().toPlainString());
            row.gstRateField().setText(line.gstRate().stripTrailingZeros().toPlainString());
        }
        recalculate();
    }

    @FXML
    private void onAddRowClicked() {
        addRow();
    }

    private void addRow() {
        List<ItemModel> models = itemModelService.search(ItemModelSearchCriteria.defaultCriteria())
                .stream().map(summary -> summary.model()).toList();

        ComboBox<ItemModel> modelCombo = new ComboBox<>(FXCollections.observableArrayList(models));
        modelCombo.setPrefWidth(200);
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
        quantitySpinner.setPrefWidth(70);

        TextField rateField = new TextField();
        rateField.setPromptText("Rate/unit (Rs)");
        rateField.setPrefWidth(110);

        TextField discountField = new TextField();
        discountField.setPromptText("Discount (Rs)");
        discountField.setPrefWidth(110);

        TextField gstRateField = new TextField();
        gstRateField.setPromptText("GST %");
        gstRateField.setPrefWidth(70);

        Button removeButton = new Button("Remove");

        HBox rowBox = new HBox(8, modelCombo, quantitySpinner, rateField, discountField, gstRateField, removeButton);
        rowBox.setAlignment(Pos.CENTER_LEFT);

        LineRowControls controls = new LineRowControls(modelCombo, quantitySpinner, rateField, discountField,
                gstRateField, rowBox);

        modelCombo.valueProperty().addListener((obs, was, model) -> {
            if (model != null && gstRateField.getText().isBlank()) {
                gstRateField.setText(model.gstRate().stripTrailingZeros().toPlainString());
            }
        });
        removeButton.setOnAction(e -> {
            rows.remove(controls);
            rowsBox.getChildren().remove(rowBox);
        });

        rows.add(controls);
        rowsBox.getChildren().add(rowBox);
    }

    @FXML
    private void onRecalculateClicked() {
        recalculate();
    }

    private void recalculate() {
        try {
            Supplier supplier = requireSupplier();
            List<PurchaseBillService.LineInput> inputs = collectLineInputs();
            PurchaseBillService.PreviewTotals totals = purchaseBillService.preview(supplier.id(), inputs,
                    parseOptionalMoney(freightField.getText(), "Freight"),
                    parseOptionalMoney(loadingChargesField.getText(), "Loading charges"),
                    parseOptionalMoney(otherChargesField.getText(), "Other charges"));
            taxableValueLabel.setText(totals.taxableValue().toDisplayString());
            cgstLabel.setText(totals.cgstAmount().toDisplayString());
            sgstLabel.setText(totals.sgstAmount().toDisplayString());
            igstLabel.setText(totals.igstAmount().toDisplayString());
            chargesLabel.setText(totals.charges().toDisplayString());
            grandTotalLabel.setText(totals.grandTotal().toDisplayString());
            errorLabel.setText("");
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private void clearTotalsLabels() {
        taxableValueLabel.setText("-");
        cgstLabel.setText("-");
        sgstLabel.setText("-");
        igstLabel.setText("-");
        chargesLabel.setText("-");
        grandTotalLabel.setText("-");
    }

    @FXML
    private void onSaveDraftClicked() {
        try {
            long billId = save();
            editingId = billId;
            pendingIsNew = false;
            titleLabel.setText("Edit Purchase Bill (Draft) - " + billNoField.getText().trim());
            errorLabel.setText("Saved as draft.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onConfirmReceiptClicked() {
        try {
            List<PurchaseBillService.LineInput> inputs = collectLineInputs();
            int totalPieces = inputs.stream().mapToInt(PurchaseBillService.LineInput::quantity).sum();

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Confirm Receipt");
            confirm.setHeaderText(null);
            confirm.setContentText("This will create " + totalPieces + " piece(s) from " + inputs.size()
                    + " line(s). Continue?");
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                return;
            }

            long billId = save();
            purchaseBillService.confirmReceipt(billId);
            sceneRouter.show("/fxml/purchase/purchase-bill-list.fxml");
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private long save() {
        Supplier supplier = requireSupplier();
        List<PurchaseBillService.LineInput> inputs = collectLineInputs();
        LocalDate billDate = billDatePicker.getValue() == null ? LocalDate.now() : billDatePicker.getValue();
        LocalDate receivedDate = receivedDatePicker.getValue() == null ? LocalDate.now() : receivedDatePicker.getValue();

        return purchaseBillService.saveDraft(editingId, supplier.id(), billNoField.getText(), billDate,
                receivedDate, parseOptionalMoney(freightField.getText(), "Freight"),
                parseOptionalMoney(loadingChargesField.getText(), "Loading charges"),
                parseOptionalMoney(otherChargesField.getText(), "Other charges"),
                notesArea.getText() == null || notesArea.getText().isBlank() ? null : notesArea.getText().trim(),
                inputs);
    }

    private Supplier requireSupplier() {
        Supplier supplier = supplierCombo.getValue();
        if (supplier == null) {
            throw new IllegalArgumentException("Select a supplier.");
        }
        return supplier;
    }

    private List<PurchaseBillService.LineInput> collectLineInputs() {
        List<PurchaseBillService.LineInput> inputs = new ArrayList<>();
        for (LineRowControls row : rows) {
            ItemModel model = row.modelCombo().getValue();
            if (model == null) {
                continue;
            }
            Money rate = parseMoney(row.rateField().getText(), "Rate");
            Money discount = parseOptionalMoney(row.discountField().getText(), "Discount");
            BigDecimal gstRate = parseDecimal(row.gstRateField().getText(), "GST rate");
            inputs.add(new PurchaseBillService.LineInput(model.id(), row.quantitySpinner().getValue(), rate,
                    discount, gstRate));
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one line item.");
        }
        return inputs;
    }

    private static Money parseMoney(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            return Money.ofRupees(new BigDecimal(text.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static Money parseOptionalMoney(String text, String label) {
        if (text == null || text.isBlank()) {
            return Money.ZERO;
        }
        try {
            return Money.ofRupees(new BigDecimal(text.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private static BigDecimal parseDecimal(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/purchase/purchase-bill-list.fxml");
    }
}
