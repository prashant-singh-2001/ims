package com.furnitureims.ui.sales;

import com.furnitureims.domain.Customer;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesLine;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.DocumentService;
import com.furnitureims.service.EmailService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.WhatsAppShareService;
import com.furnitureims.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.PropertyValueFactory;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Invoice detail (FR-SAL-11/12, FR-DOC-02/03/04; docs/03-screens.md 6.2). There is
 * deliberately no Edit button - a saved invoice is corrected by a sales return or by
 * cancellation, never rewritten.
 */
@Component
public class InvoiceDetailController {

    private final SalesInvoiceService salesInvoiceService;
    private final CustomerService customerService;
    private final PieceService pieceService;
    private final SalesReturnScreenController salesReturnScreenController;
    private final DocumentService documentService;
    private final WhatsAppShareService whatsAppShareService;
    private final EmailService emailService;
    private final SceneRouter sceneRouter;

    @FXML private Label titleLabel;
    @FXML private Label customerLabel;
    @FXML private Label placeOfSupplyLabel;
    @FXML private Label statusLabel;
    @FXML private Label grandTotalLabel;
    @FXML private Label balanceLabel;
    @FXML private Button cancelButton;
    @FXML private Button returnButton;
    @FXML private Button openPdfButton;
    @FXML private Button whatsAppButton;
    @FXML private Button emailButton;
    @FXML private Label errorLabel;

    @FXML private TableView<SalesLineRow> linesTable;
    @FXML private TableColumn<SalesLineRow, String> tagColumn;
    @FXML private TableColumn<SalesLineRow, String> descriptionColumn;
    @FXML private TableColumn<SalesLineRow, String> priceColumn;
    @FXML private TableColumn<SalesLineRow, String> discountColumn;
    @FXML private TableColumn<SalesLineRow, String> taxableColumn;
    @FXML private TableColumn<SalesLineRow, String> taxColumn;
    @FXML private TableColumn<SalesLineRow, String> totalColumn;

    private long invoiceId;

    public InvoiceDetailController(SalesInvoiceService salesInvoiceService, CustomerService customerService,
                                    PieceService pieceService, SalesReturnScreenController salesReturnScreenController,
                                    DocumentService documentService, WhatsAppShareService whatsAppShareService,
                                    EmailService emailService, SceneRouter sceneRouter) {
        this.salesInvoiceService = salesInvoiceService;
        this.customerService = customerService;
        this.pieceService = pieceService;
        this.salesReturnScreenController = salesReturnScreenController;
        this.documentService = documentService;
        this.whatsAppShareService = whatsAppShareService;
        this.emailService = emailService;
        this.sceneRouter = sceneRouter;
    }

    public void openFor(long invoiceId) {
        this.invoiceId = invoiceId;
    }

    @FXML
    private void initialize() {
        tagColumn.setCellValueFactory(new PropertyValueFactory<>("tag"));
        descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
        priceColumn.setCellValueFactory(new PropertyValueFactory<>("unitPrice"));
        discountColumn.setCellValueFactory(new PropertyValueFactory<>("discountAmount"));
        taxableColumn.setCellValueFactory(new PropertyValueFactory<>("taxableValue"));
        taxColumn.setCellValueFactory(new PropertyValueFactory<>("tax"));
        totalColumn.setCellValueFactory(new PropertyValueFactory<>("lineTotal"));
        errorLabel.setText("");
        reload();
    }

    private void reload() {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Invoice not found: " + invoiceId));
        Customer customer = customerService.findById(invoice.customerId()).orElse(null);

        titleLabel.setText("Invoice " + invoice.invoiceNo());
        customerLabel.setText(customer == null ? "-" : customer.name() + " (" + customer.phone() + ")");
        placeOfSupplyLabel.setText(invoice.placeOfSupplyStateCode() + (invoice.interstate() ? " (interstate)" : " (intrastate)"));
        statusLabel.setText(invoice.status().name()
                + (invoice.cancelReason() == null ? "" : " - " + invoice.cancelReason()));
        grandTotalLabel.setText(invoice.grandTotal().toDisplayString());
        balanceLabel.setText(salesInvoiceService.balance(invoice).toDisplayString());

        boolean active = invoice.status() == SalesInvoice.Status.ACTIVE;
        cancelButton.setDisable(!active);
        returnButton.setDisable(!active);
        boolean hasPdf = invoice.pdfPath() != null;
        openPdfButton.setDisable(!hasPdf);
        whatsAppButton.setDisable(!hasPdf);
        emailButton.setDisable(!hasPdf);

        List<SalesLine> lines = salesInvoiceService.linesFor(invoiceId);
        List<SalesLineRow> rows = lines.stream()
                .map(line -> new SalesLineRow(line, pieceService.findById(line.pieceId())
                        .map(p -> p.tag()).orElse("?")))
                .toList();
        linesTable.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onCancelClicked() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Cancel Invoice");
        dialog.setHeaderText(null);
        dialog.setContentText("Reason:");
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(reason -> {
            try {
                salesInvoiceService.cancelInvoice(invoiceId, reason);
                errorLabel.setText("");
                reload();
            } catch (IllegalArgumentException | IllegalStateException e) {
                errorLabel.setText(e.getMessage());
            }
        });
    }

    @FXML
    private void onReturnClicked() {
        salesReturnScreenController.openFor(invoiceId);
        sceneRouter.show("/fxml/sales/sales-return.fxml");
    }

    /** FR-DOC-02: also the button to press if a PDF was never generated in the first
     *  place - generating and regenerating are the same operation. */
    @FXML
    private void onGeneratePdfClicked() {
        try {
            documentService.generateInvoicePdf(invoiceId);
            errorLabel.setText("");
            reload();
        } catch (RuntimeException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onOpenPdfClicked() {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();
        try {
            Desktop.getDesktop().open(documentService.resolve(invoice.pdfPath()).toFile());
            errorLabel.setText("");
        } catch (IOException | RuntimeException e) {
            errorLabel.setText("Could not open the PDF: " + e.getMessage());
        }
    }

    @FXML
    private void onWhatsAppClicked() {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();
        Customer customer = customerService.findById(invoice.customerId()).orElse(null);
        try {
            Path pdf = documentService.resolve(invoice.pdfPath());
            whatsAppShareService.share(customer == null ? null : customer.name(),
                    customer == null ? null : customer.phone(), invoice.invoiceNo(), invoice.invoiceDate(),
                    invoice.grandTotal(), pdf);
            errorLabel.setText("");
        } catch (RuntimeException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onEmailClicked() {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();
        Customer customer = customerService.findById(invoice.customerId()).orElse(null);
        try {
            Path pdf = documentService.resolve(invoice.pdfPath());
            emailService.sendDocument(customer == null ? null : customer.email(),
                    customer == null ? null : customer.name(), invoice.invoiceNo(), invoice.invoiceDate(),
                    invoice.grandTotal(), pdf);
            errorLabel.setText("");
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setHeaderText(null);
            info.setContentText("Email sent.");
            info.showAndWait();
        } catch (RuntimeException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/sales/invoice-list.fxml");
    }
}
