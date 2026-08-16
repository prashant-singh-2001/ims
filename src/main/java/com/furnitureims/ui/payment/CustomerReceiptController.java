package com.furnitureims.ui.payment;

import com.furnitureims.domain.Customer;
import com.furnitureims.domain.Payment;
import com.furnitureims.money.Money;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.PaymentService;
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
 * Customer receipts (FR-PAY-02/03/04; docs/03-screens.md section 7): pick a customer, see
 * their unpaid invoices with balances, allocate an incoming payment across one or more of
 * them (or leave it unallocated, "on account"). {@link PaymentService} validates every
 * allocation against that invoice's own current balance - this screen only collects input
 * and surfaces whatever it rejects.
 */
@Component
public class CustomerReceiptController {

    private record AllocationRowControls(long invoiceId, HBox container, TextField allocateField) {
    }

    private final CustomerService customerService;
    private final PaymentService paymentService;
    private final SceneRouter sceneRouter;

    @FXML private TextField customerSearchField;
    @FXML private ComboBox<Customer> customerCombo;
    @FXML private Label customerDuesLabel;
    @FXML private VBox invoicesBox;
    @FXML private TextField amountField;
    @FXML private DatePicker paymentDatePicker;
    @FXML private ComboBox<Payment.Mode> modeCombo;
    @FXML private TextField referenceField;
    @FXML private TextField noteField;
    @FXML private Label errorLabel;

    private final List<AllocationRowControls> allocationRows = new ArrayList<>();

    public CustomerReceiptController(CustomerService customerService, PaymentService paymentService,
                                      SceneRouter sceneRouter) {
        this.customerService = customerService;
        this.paymentService = paymentService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        customerCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Customer c) {
                return c == null ? "" : c.name() + " (" + c.phone() + ")";
            }

            @Override
            public Customer fromString(String string) {
                return null;
            }
        });
        customerCombo.valueProperty().addListener((obs, was, customer) -> loadCustomer(customer));
        modeCombo.setItems(FXCollections.observableArrayList(Payment.Mode.values()));
        resetForm();
    }

    @FXML
    private void onSearchClicked() {
        String text = customerSearchField.getText();
        if (text == null || text.isBlank()) {
            errorLabel.setText("Enter a name or phone to search.");
            return;
        }
        List<Customer> results = customerService.search(text.trim());
        customerCombo.setItems(FXCollections.observableArrayList(results));
        if (results.isEmpty()) {
            errorLabel.setText("No matching customers.");
            return;
        }
        errorLabel.setText("");
        customerCombo.setValue(results.get(0));
        loadCustomer(results.get(0));
    }

    private void loadCustomer(Customer customer) {
        allocationRows.clear();
        invoicesBox.getChildren().clear();
        if (customer == null) {
            customerDuesLabel.setText("");
            return;
        }
        customerDuesLabel.setText("Total dues: " + paymentService.customerDues(customer.id()).toDisplayString());

        List<PaymentService.InvoiceBalanceRow> unpaid = paymentService.unpaidInvoicesFor(customer.id());
        if (unpaid.isEmpty()) {
            invoicesBox.getChildren().add(new Label("No unpaid invoices for this customer."));
            return;
        }
        for (PaymentService.InvoiceBalanceRow row : unpaid) {
            Label invoiceLabel = new Label(row.invoice().invoiceNo() + "  (" + row.invoice().invoiceDate() + ")");
            invoiceLabel.setPrefWidth(220);
            Label balanceLabel = new Label("Balance: " + row.balance().toDisplayString());
            balanceLabel.setPrefWidth(160);
            TextField allocateField = new TextField();
            allocateField.setPromptText("Allocate");
            allocateField.setPrefWidth(100);

            HBox rowBox = new HBox(10, invoiceLabel, balanceLabel, allocateField);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            invoicesBox.getChildren().add(rowBox);
            allocationRows.add(new AllocationRowControls(row.invoice().id(), rowBox, allocateField));
        }
    }

    @FXML
    private void onSaveClicked() {
        try {
            Customer customer = customerCombo.getValue();
            if (customer == null) {
                errorLabel.setText("Select a customer.");
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

            paymentService.recordCustomerPayment(customer.id(), amount, mode, nullIfBlank(referenceField.getText()),
                    nullIfBlank(noteField.getText()), date, allocations);

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Payment Recorded");
            info.setHeaderText(null);
            info.setContentText("Received " + amount.toDisplayString() + " from " + customer.name() + ".");
            info.showAndWait();

            errorLabel.setText("");
            amountField.clear();
            referenceField.clear();
            noteField.clear();
            paymentDatePicker.setValue(LocalDate.now());
            loadCustomer(customer);
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
            allocations.put(row.invoiceId(), parseMoney(text, "Allocation"));
        }
        return allocations;
    }

    private void resetForm() {
        customerSearchField.clear();
        customerCombo.setItems(FXCollections.observableArrayList());
        customerCombo.setValue(null);
        customerDuesLabel.setText("");
        allocationRows.clear();
        invoicesBox.getChildren().clear();
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
