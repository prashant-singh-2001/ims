package com.furnitureims.ui.payment;

import com.furnitureims.domain.Payment;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.service.PaymentService;
import com.furnitureims.service.SupplierService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supplier payments (FR-PAY-02/03/04; docs/03-screens.md section 7) - the mirror image of
 * {@link CustomerReceiptController} against purchase bills instead of sales invoices.
 * Suppliers are a short, fixed list per shop (unlike walk-in customers), so this picks
 * from {@link SupplierService#listActive} directly rather than searching.
 */
@Component
public class SupplierPaymentController {

    private record AllocationRowControls(long billId, HBox container, TextField allocateField) {
    }

    private final SupplierService supplierService;
    private final PaymentService paymentService;
    private final SceneRouter sceneRouter;

    @FXML private ComboBox<Supplier> supplierCombo;
    @FXML private Label supplierDuesLabel;
    @FXML private VBox billsBox;
    @FXML private TextField amountField;
    @FXML private DatePicker paymentDatePicker;
    @FXML private ComboBox<Payment.Mode> modeCombo;
    @FXML private TextField referenceField;
    @FXML private TextField noteField;
    @FXML private Label errorLabel;

    private final List<AllocationRowControls> allocationRows = new ArrayList<>();

    public SupplierPaymentController(SupplierService supplierService, PaymentService paymentService,
                                      SceneRouter sceneRouter) {
        this.supplierService = supplierService;
        this.paymentService = paymentService;
        this.sceneRouter = sceneRouter;
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
        supplierCombo.valueProperty().addListener((obs, was, supplier) -> loadSupplier(supplier));
        modeCombo.setItems(FXCollections.observableArrayList(Payment.Mode.values()));
        resetForm();
    }

    private void loadSupplier(Supplier supplier) {
        allocationRows.clear();
        billsBox.getChildren().clear();
        if (supplier == null) {
            supplierDuesLabel.setText("");
            return;
        }
        supplierDuesLabel.setText("Total dues: " + supplierService.dues(supplier.id()).toDisplayString());

        List<PaymentService.BillBalanceRow> unpaid = paymentService.unpaidBillsFor(supplier.id());
        if (unpaid.isEmpty()) {
            billsBox.getChildren().add(new Label("No unpaid bills for this supplier."));
            return;
        }
        for (PaymentService.BillBalanceRow row : unpaid) {
            Label billLabel = new Label(row.bill().supplierBillNo() + "  (" + row.bill().billDate() + ")");
            billLabel.setPrefWidth(220);
            Label balanceLabel = new Label("Balance: " + row.balance().toDisplayString());
            balanceLabel.setPrefWidth(160);
            TextField allocateField = new TextField();
            allocateField.setPromptText("Allocate");
            allocateField.setPrefWidth(100);

            HBox rowBox = new HBox(10, billLabel, balanceLabel, allocateField);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            billsBox.getChildren().add(rowBox);
            allocationRows.add(new AllocationRowControls(row.bill().id(), rowBox, allocateField));
        }
    }

    @FXML
    private void onSaveClicked() {
        try {
            Supplier supplier = supplierCombo.getValue();
            if (supplier == null) {
                errorLabel.setText("Select a supplier.");
                return;
            }
            Money amount = parseMoney(amountField.getText(), "Amount");
            Payment.Mode mode = modeCombo.getValue();
            if (mode == null) {
                errorLabel.setText("Select a payment mode.");
                return;
            }
            LocalDate date = paymentDatePicker.getValue() == null ? LocalDate.now() : paymentDatePicker.getValue();
            Map<Long, Money> allocations = collectAllocations();

            paymentService.recordSupplierPayment(supplier.id(), amount, mode, nullIfBlank(referenceField.getText()),
                    nullIfBlank(noteField.getText()), date, allocations);

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Payment Recorded");
            info.setHeaderText(null);
            info.setContentText("Paid " + amount.toDisplayString() + " to " + supplier.name() + ".");
            info.showAndWait();

            errorLabel.setText("");
            amountField.clear();
            referenceField.clear();
            noteField.clear();
            paymentDatePicker.setValue(LocalDate.now());
            loadSupplier(supplier);
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private Map<Long, Money> collectAllocations() {
        Map<Long, Money> allocations = new HashMap<>();
        for (AllocationRowControls row : allocationRows) {
            String text = row.allocateField().getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            allocations.put(row.billId(), parseMoney(text, "Allocation"));
        }
        return allocations;
    }

    private void resetForm() {
        supplierCombo.setValue(null);
        supplierDuesLabel.setText("");
        allocationRows.clear();
        billsBox.getChildren().clear();
        amountField.clear();
        paymentDatePicker.setValue(LocalDate.now());
        modeCombo.setValue(null);
        referenceField.clear();
        noteField.clear();
        errorLabel.setText("");
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

    private static String nullIfBlank(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    @FXML
    private void onPaymentListClicked() {
        sceneRouter.show("/fxml/payment/payment-list.fxml");
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/shell/dashboard-placeholder.fxml");
    }
}
