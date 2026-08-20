package com.furnitureims.ui.booking;

import com.furnitureims.domain.Customer;
import com.furnitureims.domain.LicenseState;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesLine;
import com.furnitureims.service.BookingService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.LicenseService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.ui.HasScreenTitle;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Booking detail (FR-SAL-13; docs/03-screens.md) - the invoice's items with a per-piece
 * delivered checkbox. There is deliberately no restriction on un-ticking: correcting a
 * mis-tick is fulfilment data, not an invoice edit, so FR-SAL-12's no-edit rule does not
 * apply here.
 * <p>
 * M14: unlike the write screens {@code Route} gates wholesale (New Sale, Purchase Bill
 * Entry, ...), this whole screen stays reachable in {@code WIND_DOWN} - seeing which items
 * are still undelivered is exactly the kind of thing read-only wind-down is supposed to
 * keep available. Only the one write action, ticking delivered/not-delivered, is disabled
 * here directly - see {@code Route}'s Javadoc for why this screen is deliberately not in
 * its {@code requiresLicense} list.
 */
@Component
public class BookingDetailController implements HasScreenTitle {

    private final BookingService bookingService;
    private final SalesInvoiceService salesInvoiceService;
    private final CustomerService customerService;
    private final PieceService pieceService;
    private final LicenseService licenseService;

    private final StringProperty screenTitle = new SimpleStringProperty("");

    @FXML private Label customerLabel;
    @FXML private Label invoiceLabel;
    @FXML private Label deliveredCountLabel;
    @FXML private Button markAllDeliveredButton;

    @FXML private TableView<BookingLineRow> linesTable;
    @FXML private TableColumn<BookingLineRow, String> tagColumn;
    @FXML private TableColumn<BookingLineRow, String> descriptionColumn;
    @FXML private TableColumn<BookingLineRow, Void> deliveredColumn;
    @FXML private TableColumn<BookingLineRow, String> deliveredOnColumn;

    private long invoiceId;

    public BookingDetailController(BookingService bookingService, SalesInvoiceService salesInvoiceService,
                                    CustomerService customerService, PieceService pieceService,
                                    LicenseService licenseService) {
        this.bookingService = bookingService;
        this.salesInvoiceService = salesInvoiceService;
        this.customerService = customerService;
        this.pieceService = pieceService;
        this.licenseService = licenseService;
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
        linesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tagColumn.setCellValueFactory(new PropertyValueFactory<>("tag"));
        descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
        deliveredColumn.setCellFactory(col -> deliveredCell());
        deliveredOnColumn.setCellValueFactory(new PropertyValueFactory<>("deliveredOn"));
        reload();
    }

    private TableCell<BookingLineRow, Void> deliveredCell() {
        return new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                checkBox.setOnAction(e -> {
                    BookingLineRow row = getTableView().getItems().get(getIndex());
                    if (checkBox.isSelected()) {
                        bookingService.markDelivered(row.getLineId());
                    } else {
                        bookingService.markNotDelivered(row.getLineId());
                    }
                    reload();
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                BookingLineRow row = getTableView().getItems().get(getIndex());
                checkBox.setSelected(row.isDelivered());
                checkBox.setDisable(licenseService.state() == LicenseState.WIND_DOWN);
                setGraphic(checkBox);
            }
        };
    }

    private void reload() {
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Invoice not found: " + invoiceId));
        Customer customer = customerService.findById(invoice.customerId()).orElse(null);

        screenTitle.set("Booking " + invoice.invoiceNo());
        customerLabel.setText(customer == null ? "-" : customer.name() + " (" + customer.phone() + ")");
        invoiceLabel.setText(invoice.invoiceNo() + " - " + invoice.invoiceDate());

        List<SalesLine> lines = bookingService.linesFor(invoiceId);
        long deliveredCount = lines.stream().filter(l -> l.deliveredAt() != null).count();
        deliveredCountLabel.setText(deliveredCount + " of " + lines.size() + " delivered");
        markAllDeliveredButton.setDisable(deliveredCount == lines.size()
                || licenseService.state() == LicenseState.WIND_DOWN);

        List<BookingLineRow> rows = lines.stream()
                .map(line -> new BookingLineRow(line, pieceService.findById(line.pieceId())
                        .map(p -> p.tag()).orElse("?")))
                .toList();
        linesTable.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onMarkAllDeliveredClicked() {
        bookingService.markAllDelivered(invoiceId);
        reload();
    }

}
