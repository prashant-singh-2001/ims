package com.piecetrack.ui.sales;

import com.piecetrack.repository.SalesInvoiceListRow;
import com.piecetrack.repository.SalesInvoiceSearchCriteria;
import com.piecetrack.service.SalesInvoiceService;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Invoices list (docs/03-screens.md 6.2). */
@Component
public class InvoiceListController {

    private final SalesInvoiceService salesInvoiceService;
    private final InvoiceDetailController invoiceDetailController;
    private final SceneRouter sceneRouter;

    @FXML private TableView<InvoiceRow> table;
    @FXML private TableColumn<InvoiceRow, String> invoiceNoColumn;
    @FXML private TableColumn<InvoiceRow, String> dateColumn;
    @FXML private TableColumn<InvoiceRow, String> customerColumn;
    @FXML private TableColumn<InvoiceRow, String> totalColumn;
    @FXML private TableColumn<InvoiceRow, String> balanceColumn;
    @FXML private TableColumn<InvoiceRow, String> statusColumn;
    @FXML private TableColumn<InvoiceRow, Void> actionsColumn;

    public InvoiceListController(SalesInvoiceService salesInvoiceService,
                                  InvoiceDetailController invoiceDetailController, SceneRouter sceneRouter) {
        this.salesInvoiceService = salesInvoiceService;
        this.invoiceDetailController = invoiceDetailController;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        invoiceNoColumn.setCellValueFactory(new PropertyValueFactory<>("invoiceNo"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("invoiceDate"));
        customerColumn.setCellValueFactory(new PropertyValueFactory<>("customerName"));
        totalColumn.setCellValueFactory(new PropertyValueFactory<>("grandTotal"));
        balanceColumn.setCellValueFactory(new PropertyValueFactory<>("balance"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        actionsColumn.setCellFactory(col -> viewActionCell());
        reload();
    }

    private TableCell<InvoiceRow, Void> viewActionCell() {
        return new TableCell<>() {
            private final Button viewButton = new Button("View");

            {
                viewButton.setOnAction(e -> {
                    InvoiceRow row = getTableView().getItems().get(getIndex());
                    invoiceDetailController.openFor(row.getId());
                    sceneRouter.navigate(Route.INVOICE_DETAIL);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : viewButton);
            }
        };
    }

    private void reload() {
        List<SalesInvoiceListRow> results = salesInvoiceService.search(SalesInvoiceSearchCriteria.empty());
        List<InvoiceRow> rows = results.stream()
                .map(r -> new InvoiceRow(r, salesInvoiceService.balance(r.invoice())))
                .toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onNewSaleClicked() {
        sceneRouter.navigate(Route.NEW_SALE);
    }

}
