package com.piecetrack.ui.sales;

import com.piecetrack.domain.Customer;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.domain.SalesLine;
import com.piecetrack.domain.SalesReturn;
import com.piecetrack.service.CustomerService;
import com.piecetrack.service.DocumentService;
import com.piecetrack.service.EmailService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.SalesInvoiceService;
import com.piecetrack.service.SalesReturnService;
import com.piecetrack.service.WhatsAppShareService;
import com.piecetrack.ui.HasScreenTitle;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sales return (FR-SAL-10, FR-DOC-05; docs/03-screens.md 6.3): pick which sold pieces
 *  from an active invoice come back. */
@Component
public class SalesReturnScreenController implements HasScreenTitle {

    private final SalesInvoiceService salesInvoiceService;
    private final SalesReturnService salesReturnService;
    private final PieceService pieceService;
    private final CustomerService customerService;
    private final DocumentService documentService;
    private final WhatsAppShareService whatsAppShareService;
    private final EmailService emailService;
    private final SceneRouter sceneRouter;

    /** M10: see ItemModelEditorController.screenTitle for the pattern this follows. */
    private final StringProperty screenTitle = new SimpleStringProperty("");

    @FXML private VBox pieceCheckboxesBox;
    @FXML private TextField reasonField;
    @FXML private DatePicker returnDatePicker;
    @FXML private RadioButton adjustAgainstDueRadio;
    @FXML private RadioButton cashRefundRadio;
    @FXML private Label errorLabel;

    private final Map<CheckBox, Long> checkboxToPieceId = new LinkedHashMap<>();
    private long invoiceId;

    public SalesReturnScreenController(SalesInvoiceService salesInvoiceService, SalesReturnService salesReturnService,
                                        PieceService pieceService, CustomerService customerService,
                                        DocumentService documentService, WhatsAppShareService whatsAppShareService,
                                        EmailService emailService, SceneRouter sceneRouter) {
        this.salesInvoiceService = salesInvoiceService;
        this.salesReturnService = salesReturnService;
        this.pieceService = pieceService;
        this.customerService = customerService;
        this.documentService = documentService;
        this.whatsAppShareService = whatsAppShareService;
        this.emailService = emailService;
        this.sceneRouter = sceneRouter;
    }

    public void openFor(long invoiceId) {
        this.invoiceId = invoiceId;
    }

    @Override
    public ReadOnlyStringProperty screenTitleProperty() {
        return screenTitle;
    }

    @FXML
    private void initialize() {
        errorLabel.setText("");
        returnDatePicker.setValue(LocalDate.now());
        ToggleGroup group = new ToggleGroup();
        adjustAgainstDueRadio.setToggleGroup(group);
        cashRefundRadio.setToggleGroup(group);
        adjustAgainstDueRadio.setSelected(true);
        reload();
    }

    private void reload() {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Invoice not found: " + invoiceId));
        screenTitle.set("Return Pieces - Invoice " + invoice.invoiceNo());

        pieceCheckboxesBox.getChildren().clear();
        checkboxToPieceId.clear();

        List<SalesLine> lines = salesInvoiceService.linesFor(invoiceId);
        boolean any = false;
        for (SalesLine line : lines) {
            boolean sold = pieceService.findById(line.pieceId())
                    .map(p -> p.state().name().equals("SOLD")).orElse(false);
            if (!sold) {
                continue;
            }
            any = true;
            CheckBox checkBox = new CheckBox(pieceService.findById(line.pieceId()).map(p -> p.tag()).orElse("?")
                    + " - " + line.descriptionSnapshot() + " - " + line.lineTotal().toDisplayString());
            pieceCheckboxesBox.getChildren().add(checkBox);
            checkboxToPieceId.put(checkBox, line.pieceId());
        }
        if (!any) {
            pieceCheckboxesBox.getChildren().add(
                    new Label("No pieces from this invoice are currently sold and eligible for return."));
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
            SalesReturn.RefundMode mode = cashRefundRadio.isSelected()
                    ? SalesReturn.RefundMode.CASH_REFUND : SalesReturn.RefundMode.ADJUST_AGAINST_DUE;
            LocalDate returnDate = returnDatePicker.getValue() == null ? LocalDate.now() : returnDatePicker.getValue();

            long returnId = salesReturnService.createReturn(invoiceId, selected, reasonField.getText(),
                    returnDate, mode);
            SalesReturn salesReturn = salesReturnService.returnsFor(invoiceId).stream()
                    .filter(r -> r.id() == returnId).findFirst().orElseThrow();

            Path pdfPath;
            String pdfNote;
            try {
                pdfPath = documentService.generateCreditNotePdf(returnId);
                pdfNote = "";
            } catch (RuntimeException e) {
                pdfPath = null;
                pdfNote = "\n\nThe return was saved, but the PDF could not be generated: " + e.getMessage();
            }

            showCreditNoteDialog(salesReturn, selected.size(), pdfNote, pdfPath);
            sceneRouter.navigate(Route.INVOICE_LIST);
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    /** Print/WhatsApp/Email keep the dialog open (event filter consumes the click) so more
     *  than one can be used before continuing - the same pattern NewSaleController uses. */
    private void showCreditNoteDialog(SalesReturn salesReturn, int pieceCount, String pdfNote, Path pdfPath) {
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("Return Recorded");
        info.setHeaderText("Credit note " + salesReturn.creditNoteNo());
        info.setContentText("Credited " + salesReturn.totalAmount().toDisplayString()
                + " for " + pieceCount + " piece(s)." + pdfNote);

        ButtonType openPdfType = new ButtonType("Open PDF");
        ButtonType whatsAppType = new ButtonType("WhatsApp");
        ButtonType emailType = new ButtonType("Email");
        ButtonType doneType = new ButtonType("Done", ButtonBar.ButtonData.OK_DONE);
        info.getButtonTypes().setAll(openPdfType, whatsAppType, emailType, doneType);

        ((Button) info.getDialogPane().lookupButton(openPdfType)).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            openPdf(pdfPath);
        });
        ((Button) info.getDialogPane().lookupButton(whatsAppType)).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            shareWhatsApp(salesReturn, pdfPath);
        });
        ((Button) info.getDialogPane().lookupButton(emailType)).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            sendEmail(salesReturn, pdfPath);
        });

        info.showAndWait();
    }

    private void openPdf(Path pdfPath) {
        if (pdfPath == null) {
            showActionError("The PDF was not generated for this credit note.");
            return;
        }
        try {
            Desktop.getDesktop().open(pdfPath.toFile());
        } catch (IOException e) {
            showActionError("Could not open the PDF: " + e.getMessage());
        }
    }

    private void shareWhatsApp(SalesReturn salesReturn, Path pdfPath) {
        if (pdfPath == null) {
            showActionError("The PDF was not generated for this credit note.");
            return;
        }
        try {
            SalesInvoice invoice = salesInvoiceService.findById(salesReturn.salesInvoiceId()).orElseThrow();
            Customer customer = customerService.findById(invoice.customerId()).orElseThrow();
            whatsAppShareService.share(customer.name(), customer.phone(), salesReturn.creditNoteNo(),
                    salesReturn.returnDate(), salesReturn.totalAmount(), pdfPath);
        } catch (RuntimeException e) {
            showActionError(e.getMessage());
        }
    }

    private void sendEmail(SalesReturn salesReturn, Path pdfPath) {
        if (pdfPath == null) {
            showActionError("The PDF was not generated for this credit note.");
            return;
        }
        try {
            SalesInvoice invoice = salesInvoiceService.findById(salesReturn.salesInvoiceId()).orElseThrow();
            Customer customer = customerService.findById(invoice.customerId()).orElseThrow();
            emailService.sendDocument(customer.email(), customer.name(), salesReturn.creditNoteNo(),
                    salesReturn.returnDate(), salesReturn.totalAmount(), pdfPath);
        } catch (RuntimeException e) {
            showActionError(e.getMessage());
        }
    }

    private static void showActionError(String message) {
        Alert error = new Alert(Alert.AlertType.ERROR);
        error.setHeaderText(null);
        error.setContentText(message);
        error.showAndWait();
    }

}
