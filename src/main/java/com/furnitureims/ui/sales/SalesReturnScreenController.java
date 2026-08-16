package com.furnitureims.ui.sales;

import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesLine;
import com.furnitureims.domain.SalesReturn;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.SalesReturnService;
import com.furnitureims.ui.SceneRouter;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sales return (FR-SAL-10; docs/03-screens.md 6.3): pick which sold pieces from an
 *  active invoice come back. */
@Component
public class SalesReturnScreenController {

    private final SalesInvoiceService salesInvoiceService;
    private final SalesReturnService salesReturnService;
    private final PieceService pieceService;
    private final SceneRouter sceneRouter;

    @FXML private Label titleLabel;
    @FXML private VBox pieceCheckboxesBox;
    @FXML private TextField reasonField;
    @FXML private DatePicker returnDatePicker;
    @FXML private RadioButton adjustAgainstDueRadio;
    @FXML private RadioButton cashRefundRadio;
    @FXML private Label errorLabel;

    private final Map<CheckBox, Long> checkboxToPieceId = new LinkedHashMap<>();
    private long invoiceId;

    public SalesReturnScreenController(SalesInvoiceService salesInvoiceService, SalesReturnService salesReturnService,
                                        PieceService pieceService, SceneRouter sceneRouter) {
        this.salesInvoiceService = salesInvoiceService;
        this.salesReturnService = salesReturnService;
        this.pieceService = pieceService;
        this.sceneRouter = sceneRouter;
    }

    public void openFor(long invoiceId) {
        this.invoiceId = invoiceId;
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
        titleLabel.setText("Return Pieces - Invoice " + invoice.invoiceNo());

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

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Return Recorded");
            info.setHeaderText("Credit note " + salesReturn.creditNoteNo());
            info.setContentText("Credited " + salesReturn.totalAmount().toDisplayString()
                    + " for " + selected.size() + " piece(s).");
            info.showAndWait();

            sceneRouter.show("/fxml/sales/invoice-list.fxml");
        } catch (IllegalArgumentException | IllegalStateException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onBackClicked() {
        sceneRouter.show("/fxml/sales/invoice-list.fxml");
    }
}
