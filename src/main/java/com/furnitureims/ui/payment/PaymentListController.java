package com.furnitureims.ui.payment;

import com.furnitureims.domain.Customer;
import com.furnitureims.domain.Payment;
import com.furnitureims.domain.Supplier;
import com.furnitureims.repository.PaymentSearchCriteria;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.PaymentService;
import com.furnitureims.service.SupplierService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.StringConverter;
import org.springframework.stereotype.Component;

import java.util.List;

/** Payment list, both directions (FR-PAY-05/06; docs/03-screens.md section 7). */
@Component
public class PaymentListController {

    private final PaymentService paymentService;
    private final CustomerService customerService;
    private final SupplierService supplierService;
    private final SceneRouter sceneRouter;

    @FXML private ComboBox<Payment.PartyType> partyTypeFilterCombo;
    @FXML private ComboBox<Payment.Mode> modeFilterCombo;
    @FXML private DatePicker dateFromPicker;
    @FXML private DatePicker dateToPicker;
    @FXML private CheckBox showDeletedCheck;
    @FXML private Label errorLabel;

    @FXML private TableView<PaymentRow> table;
    @FXML private TableColumn<PaymentRow, String> directionColumn;
    @FXML private TableColumn<PaymentRow, String> dateColumn;
    @FXML private TableColumn<PaymentRow, String> partyColumn;
    @FXML private TableColumn<PaymentRow, String> amountColumn;
    @FXML private TableColumn<PaymentRow, String> modeColumn;
    @FXML private TableColumn<PaymentRow, String> referenceColumn;
    @FXML private TableColumn<PaymentRow, String> statusColumn;
    @FXML private TableColumn<PaymentRow, Void> actionsColumn;

    public PaymentListController(PaymentService paymentService, CustomerService customerService,
                                  SupplierService supplierService, SceneRouter sceneRouter) {
        this.paymentService = paymentService;
        this.customerService = customerService;
        this.supplierService = supplierService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        directionColumn.setCellValueFactory(new PropertyValueFactory<>("direction"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        partyColumn.setCellValueFactory(new PropertyValueFactory<>("partyName"));
        amountColumn.setCellValueFactory(new PropertyValueFactory<>("amount"));
        modeColumn.setCellValueFactory(new PropertyValueFactory<>("mode"));
        referenceColumn.setCellValueFactory(new PropertyValueFactory<>("reference"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        actionsColumn.setCellFactory(col -> deleteActionCell());

        partyTypeFilterCombo.getItems().add(null);
        partyTypeFilterCombo.getItems().addAll(Payment.PartyType.values());
        partyTypeFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Payment.PartyType type) {
                if (type == null) {
                    return "All parties";
                }
                return type == Payment.PartyType.CUSTOMER ? "Customer receipts (IN)" : "Supplier payments (OUT)";
            }

            @Override
            public Payment.PartyType fromString(String string) {
                return null;
            }
        });

        modeFilterCombo.getItems().add(null);
        modeFilterCombo.getItems().addAll(Payment.Mode.values());
        modeFilterCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Payment.Mode mode) {
                return mode == null ? "All modes" : mode.name();
            }

            @Override
            public Payment.Mode fromString(String string) {
                return null;
            }
        });

        errorLabel.setText("");
        reload();
    }

    private TableCell<PaymentRow, Void> deleteActionCell() {
        return new TableCell<>() {
            private final Button deleteButton = new Button("Delete");

            {
                deleteButton.setOnAction(e -> onDelete(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                deleteButton.setDisable(getTableView().getItems().get(getIndex()).getPayment().deleted());
                setGraphic(deleteButton);
            }
        };
    }

    private void onDelete(PaymentRow row) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Delete Payment");
        dialog.setHeaderText(null);
        dialog.setContentText("Reason:");
        dialog.showAndWait().ifPresent(reason -> {
            try {
                paymentService.deletePayment(row.getId(), reason);
                errorLabel.setText("");
                reload();
            } catch (IllegalArgumentException | IllegalStateException e) {
                errorLabel.setText(e.getMessage());
            }
        });
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

    private void reload() {
        PaymentSearchCriteria criteria = new PaymentSearchCriteria(partyTypeFilterCombo.getValue(), null,
                modeFilterCombo.getValue(), dateFromPicker.getValue(), dateToPicker.getValue(),
                showDeletedCheck.isSelected());
        List<Payment> payments = paymentService.search(criteria);
        List<PaymentRow> rows = payments.stream().map(this::toRow).toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    private PaymentRow toRow(Payment payment) {
        String partyName;
        if (payment.partyType() == Payment.PartyType.CUSTOMER) {
            partyName = customerService.findById(payment.partyId()).map(Customer::name).orElse("?");
        } else {
            partyName = supplierService.findById(payment.partyId()).map(Supplier::name).orElse("?");
        }
        return new PaymentRow(payment, partyName);
    }

    @FXML
    private void onCustomerReceiptClicked() {
        sceneRouter.show("/fxml/payment/customer-receipt.fxml");
    }

    @FXML
    private void onSupplierPaymentClicked() {
        sceneRouter.show("/fxml/payment/supplier-payment.fxml");
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/shell/dashboard-placeholder.fxml");
    }
}
