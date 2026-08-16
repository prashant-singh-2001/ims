package com.furnitureims.ui.sales;

import com.furnitureims.domain.Customer;
import com.furnitureims.domain.IndianState;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Payment;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ItemModelSearchCriteria;
import com.furnitureims.repository.PieceSearchCriteria;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.ui.catalogue.PieceRow;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
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
 * New Sale (FR-SAL-01..09,12; docs/03-screens.md 6.1) - the most important screen in the
 * application. Only IN_STOCK pieces are ever offered, both from the model-based picker
 * and from direct tag entry. Totals are driven entirely by
 * {@link SalesInvoiceService#preview}, the same computation {@link #onSaveClicked}
 * ultimately persists, so Recalculate never lies about what Save will do.
 * <p>
 * The advance panel (FR-PAY-01) is optional - a blank amount records the sale with no
 * payment, same as before milestone M5. PDF/print/WhatsApp/email actions after saving are
 * still milestone M6 and intentionally absent.
 */
@Component
public class NewSaleController {

    private record BillLineControls(long pieceId, String tag, TextField priceField, TextField discountField,
                                     Label taxableLabel, Label taxLabel, Label totalLabel, HBox container) {
    }

    private final CustomerService customerService;
    private final ItemModelService itemModelService;
    private final PieceService pieceService;
    private final SalesInvoiceService salesInvoiceService;
    private final SceneRouter sceneRouter;

    @FXML private TextField phoneField;
    @FXML private Label customerStatusLabel;
    @FXML private TextField nameField;
    @FXML private TextField addressLine1Field;
    @FXML private TextField addressLine2Field;
    @FXML private TextField cityField;
    @FXML private TextField pincodeField;
    @FXML private ComboBox<IndianState> customerStateCombo;
    @FXML private ComboBox<IndianState> placeOfSupplyCombo;
    @FXML private TextField gstinField;

    @FXML private ComboBox<ItemModel> modelSearchCombo;
    @FXML private TableView<PieceRow> piecesTable;
    @FXML private TableColumn<PieceRow, String> pieceTagColumn;
    @FXML private TableColumn<PieceRow, String> pieceModelColumn;
    @FXML private TableColumn<PieceRow, String> pieceLocationColumn;
    @FXML private TableColumn<PieceRow, String> pieceCostColumn;
    @FXML private TableColumn<PieceRow, Void> pieceActionsColumn;
    @FXML private TextField tagField;

    @FXML private VBox rowsBox;
    @FXML private CheckBox priceInclusiveCheck;
    @FXML private TextField billDiscountField;

    @FXML private Label grossValueLabel;
    @FXML private Label lineDiscountLabel;
    @FXML private Label taxableValueLabel;
    @FXML private Label cgstLabel;
    @FXML private Label sgstLabel;
    @FXML private Label igstLabel;
    @FXML private Label roundOffLabel;
    @FXML private Label grandTotalLabel;
    @FXML private Label warningLabel;
    @FXML private Label errorLabel;

    @FXML private TextField advanceAmountField;
    @FXML private ComboBox<Payment.Mode> advanceModeCombo;
    @FXML private TextField advanceReferenceField;
    @FXML private TextField advanceNoteField;

    private final List<BillLineControls> rows = new ArrayList<>();
    private Long existingCustomerId;

    public NewSaleController(CustomerService customerService, ItemModelService itemModelService,
                              PieceService pieceService, SalesInvoiceService salesInvoiceService,
                              SceneRouter sceneRouter) {
        this.customerService = customerService;
        this.itemModelService = itemModelService;
        this.pieceService = pieceService;
        this.salesInvoiceService = salesInvoiceService;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        modelSearchCombo.setItems(FXCollections.observableArrayList(
                itemModelService.search(ItemModelSearchCriteria.defaultCriteria())
                        .stream().map(summary -> summary.model()).toList()));
        modelSearchCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ItemModel m) {
                return m == null ? "" : m.modelName() + " (" + m.modelCode() + ")";
            }

            @Override
            public ItemModel fromString(String s) {
                return null;
            }
        });
        modelSearchCombo.valueProperty().addListener((obs, was, isNow) -> onModelSelected());

        StringConverter<IndianState> stateConverter = new StringConverter<>() {
            @Override
            public String toString(IndianState s) {
                return s == null ? "" : s.displayName();
            }

            @Override
            public IndianState fromString(String s) {
                return null;
            }
        };
        customerStateCombo.setItems(FXCollections.observableArrayList(IndianState.values()));
        customerStateCombo.setConverter(stateConverter);
        placeOfSupplyCombo.setItems(FXCollections.observableArrayList(IndianState.values()));
        placeOfSupplyCombo.setConverter(stateConverter);
        customerStateCombo.valueProperty().addListener((obs, was, state) -> {
            if (placeOfSupplyCombo.getValue() == null && state != null) {
                placeOfSupplyCombo.setValue(state);
            }
        });

        advanceModeCombo.setItems(FXCollections.observableArrayList(Payment.Mode.values()));

        pieceTagColumn.setCellValueFactory(new PropertyValueFactory<>("tag"));
        pieceModelColumn.setCellValueFactory(new PropertyValueFactory<>("modelName"));
        pieceLocationColumn.setCellValueFactory(new PropertyValueFactory<>("locationName"));
        pieceCostColumn.setCellValueFactory(new PropertyValueFactory<>("landedCost"));
        pieceActionsColumn.setCellFactory(col -> addPieceActionCell());

        resetForNewSale();
    }

    private TableCell<PieceRow, Void> addPieceActionCell() {
        return new TableCell<>() {
            private final Button addButton = new Button("Add");

            {
                addButton.setOnAction(e -> {
                    Piece piece = getTableView().getItems().get(getIndex()).getSummary().piece();
                    itemModelService.findById(piece.itemModelId()).ifPresent(model -> addBillLine(piece, model));
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : addButton);
            }
        };
    }

    private void onModelSelected() {
        ItemModel model = modelSearchCombo.getValue();
        if (model == null) {
            piecesTable.setItems(FXCollections.observableArrayList());
            return;
        }
        List<PieceRow> available = pieceService.search(
                        new PieceSearchCriteria(null, Piece.State.IN_STOCK, model.id(), null, null, null, null))
                .stream().map(PieceRow::new).toList();
        piecesTable.setItems(FXCollections.observableArrayList(available));
    }

    @FXML
    private void onAddByTagClicked() {
        String tag = tagField.getText();
        if (tag == null || tag.isBlank()) {
            errorLabel.setText("Enter a tag.");
            return;
        }
        Piece piece = pieceService.findByTag(tag.trim()).orElse(null);
        if (piece == null) {
            errorLabel.setText("No piece found with tag \"" + tag.trim() + "\".");
            return;
        }
        if (piece.state() != Piece.State.IN_STOCK) {
            errorLabel.setText("Piece " + piece.tag() + " is " + piece.state() + " and cannot be sold.");
            return;
        }
        itemModelService.findById(piece.itemModelId()).ifPresent(model -> {
            addBillLine(piece, model);
            tagField.clear();
        });
    }

    private void addBillLine(Piece piece, ItemModel model) {
        if (rows.stream().anyMatch(r -> r.pieceId() == piece.id())) {
            errorLabel.setText("Piece " + piece.tag() + " is already on this bill.");
            return;
        }

        Label tagModelLabel = new Label(piece.tag() + " - " + model.modelName());
        tagModelLabel.setPrefWidth(220);

        TextField priceField = new TextField(
                model.defaultSalePrice() == null ? "" : model.defaultSalePrice().rupees().toPlainString());
        priceField.setPromptText("Price");
        priceField.setPrefWidth(100);

        TextField discountField = new TextField();
        discountField.setPromptText("Discount");
        discountField.setPrefWidth(90);

        Label taxableLabel = new Label("-");
        taxableLabel.setPrefWidth(90);
        Label taxLabel = new Label("-");
        taxLabel.setPrefWidth(90);
        Label totalLabel = new Label("-");
        totalLabel.setPrefWidth(90);

        Button removeButton = new Button("Remove");

        HBox rowBox = new HBox(8, tagModelLabel, priceField, discountField, taxableLabel, taxLabel, totalLabel,
                removeButton);
        rowBox.setAlignment(Pos.CENTER_LEFT);

        BillLineControls controls = new BillLineControls(piece.id(), piece.tag(), priceField, discountField,
                taxableLabel, taxLabel, totalLabel, rowBox);
        removeButton.setOnAction(e -> {
            rows.remove(controls);
            rowsBox.getChildren().remove(rowBox);
        });

        rows.add(controls);
        rowsBox.getChildren().add(rowBox);
        errorLabel.setText("");
    }

    @FXML
    private void onLookUpCustomerClicked() {
        String phone = phoneField.getText();
        if (phone == null || phone.isBlank()) {
            errorLabel.setText("Enter a phone number to look up.");
            return;
        }
        Optional<Customer> found = customerService.findByPhone(phone);
        if (found.isPresent()) {
            Customer c = found.get();
            existingCustomerId = c.id();
            nameField.setText(c.name());
            addressLine1Field.setText(c.addressLine1());
            addressLine2Field.setText(c.addressLine2());
            cityField.setText(c.city());
            pincodeField.setText(c.pincode());
            if (c.stateCode() != null) {
                IndianState state = IndianState.byGstCode(c.stateCode());
                customerStateCombo.setValue(state);
                placeOfSupplyCombo.setValue(state);
            }
            gstinField.setText(c.gstin());
            customerStatusLabel.setText("Existing customer");
        } else {
            existingCustomerId = null;
            customerStatusLabel.setText("New customer - fill in the details below");
        }
        errorLabel.setText("");
    }

    @FXML
    private void onRecalculateClicked() {
        recalculate();
    }

    private void recalculate() {
        try {
            IndianState placeOfSupply = placeOfSupplyCombo.getValue();
            if (placeOfSupply == null) {
                errorLabel.setText("Select the place of supply.");
                return;
            }
            List<SalesInvoiceService.InvoiceLineInput> inputs = collectLineInputs();
            Money billDiscount = parseOptionalMoney(billDiscountField.getText(), "Bill discount");

            SalesInvoiceService.InvoicePreview preview = salesInvoiceService.preview(placeOfSupply.gstCode(),
                    inputs, priceInclusiveCheck.isSelected(), billDiscount);

            StringBuilder warnings = new StringBuilder();
            for (SalesInvoiceService.LinePreview lp : preview.lines()) {
                rows.stream().filter(r -> r.pieceId() == lp.pieceId()).findFirst().ifPresent(row -> {
                    row.taxableLabel().setText(lp.taxableValue().toDisplayString());
                    row.taxLabel().setText(lp.cgst().plus(lp.sgst()).plus(lp.igst()).toDisplayString());
                    row.totalLabel().setText(lp.lineTotal().toDisplayString());
                });
                pieceService.findById(lp.pieceId()).ifPresent(piece -> {
                    if (lp.unitPriceEntered().compareTo(piece.landedCost()) < 0) {
                        if (!warnings.isEmpty()) {
                            warnings.append("; ");
                        }
                        warnings.append(piece.tag()).append(" is priced below its cost of ")
                                .append(piece.landedCost().toDisplayString());
                    }
                });
            }

            grossValueLabel.setText(preview.grossValue().toDisplayString());
            lineDiscountLabel.setText(preview.lineDiscountTotal().toDisplayString());
            taxableValueLabel.setText(preview.taxableValue().toDisplayString());
            cgstLabel.setText(preview.cgstAmount().toDisplayString());
            sgstLabel.setText(preview.sgstAmount().toDisplayString());
            igstLabel.setText(preview.igstAmount().toDisplayString());
            roundOffLabel.setText(preview.roundOff().toDisplayString());
            grandTotalLabel.setText(preview.grandTotal().toDisplayString());
            warningLabel.setText(warnings.toString());
            errorLabel.setText("");
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onSaveClicked() {
        try {
            IndianState placeOfSupply = placeOfSupplyCombo.getValue();
            if (placeOfSupply == null) {
                errorLabel.setText("Select the place of supply.");
                return;
            }
            List<SalesInvoiceService.InvoiceLineInput> inputs = collectLineInputs();
            Money billDiscount = parseOptionalMoney(billDiscountField.getText(), "Bill discount");
            long customerId = resolveCustomerId();

            Money advanceAmount = parseOptionalMoney(advanceAmountField.getText(), "Advance amount");
            Payment.Mode advanceMode = advanceAmount.isPositive() ? advanceModeCombo.getValue() : null;
            if (advanceAmount.isPositive() && advanceMode == null) {
                errorLabel.setText("Select a mode for the advance payment.");
                return;
            }

            long invoiceId = salesInvoiceService.createInvoice(customerId, placeOfSupply.gstCode(),
                    priceInclusiveCheck.isSelected(), inputs, billDiscount, LocalDate.now(),
                    advanceAmount, advanceMode, nullIfBlank(advanceReferenceField.getText()),
                    nullIfBlank(advanceNoteField.getText()));
            SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Sale Recorded");
            info.setHeaderText("Invoice " + invoice.invoiceNo());
            String content = "Grand total: " + invoice.grandTotal().toDisplayString();
            if (advanceAmount.isPositive()) {
                content += "\nAdvance received: " + advanceAmount.toDisplayString()
                        + "\nBalance due: " + salesInvoiceService.balance(invoice).toDisplayString();
            }
            info.setContentText(content);
            info.showAndWait();

            resetForNewSale();
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private long resolveCustomerId() {
        if (existingCustomerId != null) {
            return existingCustomerId;
        }
        IndianState state = customerStateCombo.getValue();
        Customer draft = new Customer(0, requireText(nameField.getText(), "Customer name"),
                requireText(phoneField.getText(), "Customer phone"), nullIfBlank(addressLine1Field.getText()),
                nullIfBlank(addressLine2Field.getText()), nullIfBlank(cityField.getText()),
                nullIfBlank(pincodeField.getText()), state == null ? null : state.displayName(),
                state == null ? null : state.gstCode(), nullIfBlank(gstinField.getText()), null);
        return customerService.create(draft);
    }

    private List<SalesInvoiceService.InvoiceLineInput> collectLineInputs() {
        List<SalesInvoiceService.InvoiceLineInput> inputs = new ArrayList<>();
        for (BillLineControls row : rows) {
            Money price = parseMoney(row.priceField().getText(), "Price for " + row.tag());
            Money discount = parseOptionalMoney(row.discountField().getText(), "Discount for " + row.tag());
            inputs.add(new SalesInvoiceService.InvoiceLineInput(row.pieceId(), price, discount));
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item to the bill.");
        }
        return inputs;
    }

    private void resetForNewSale() {
        existingCustomerId = null;
        phoneField.clear();
        nameField.clear();
        addressLine1Field.clear();
        addressLine2Field.clear();
        cityField.clear();
        pincodeField.clear();
        customerStateCombo.setValue(null);
        placeOfSupplyCombo.setValue(null);
        gstinField.clear();
        customerStatusLabel.setText("");

        modelSearchCombo.setValue(null);
        piecesTable.setItems(FXCollections.observableArrayList());
        tagField.clear();

        rows.clear();
        rowsBox.getChildren().clear();
        priceInclusiveCheck.setSelected(false);
        billDiscountField.clear();

        advanceAmountField.clear();
        advanceModeCombo.setValue(null);
        advanceReferenceField.clear();
        advanceNoteField.clear();

        grossValueLabel.setText("-");
        lineDiscountLabel.setText("-");
        taxableValueLabel.setText("-");
        cgstLabel.setText("-");
        sgstLabel.setText("-");
        igstLabel.setText("-");
        roundOffLabel.setText("-");
        grandTotalLabel.setText("-");
        warningLabel.setText("");
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

    private static String requireText(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return text.trim();
    }

    private static String nullIfBlank(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/sales/invoice-list.fxml");
    }
}
