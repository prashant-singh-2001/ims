package com.piecetrack.ui.booking;

import com.piecetrack.repository.BookingListRow;
import com.piecetrack.service.BookingService;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import org.springframework.stereotype.Component;

import java.util.List;

/** Bookings list (FR-SAL-13; docs/03-screens.md). */
@Component
public class BookingListController {

    private final BookingService bookingService;
    private final BookingDetailController bookingDetailController;
    private final SceneRouter sceneRouter;

    @FXML private CheckBox showDeliveredCheck;

    @FXML private TableView<BookingRow> table;
    @FXML private TableColumn<BookingRow, String> invoiceNoColumn;
    @FXML private TableColumn<BookingRow, String> dateColumn;
    @FXML private TableColumn<BookingRow, String> customerColumn;
    @FXML private TableColumn<BookingRow, String> itemsColumn;
    @FXML private TableColumn<BookingRow, String> statusColumn;
    @FXML private TableColumn<BookingRow, Void> actionsColumn;

    public BookingListController(BookingService bookingService, BookingDetailController bookingDetailController,
                                  SceneRouter sceneRouter) {
        this.bookingService = bookingService;
        this.bookingDetailController = bookingDetailController;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        invoiceNoColumn.setCellValueFactory(new PropertyValueFactory<>("invoiceNo"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("invoiceDate"));
        customerColumn.setCellValueFactory(new PropertyValueFactory<>("customerName"));
        itemsColumn.setCellValueFactory(new PropertyValueFactory<>("items"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        actionsColumn.setCellFactory(col -> viewActionCell());
        reload();
    }

    private TableCell<BookingRow, Void> viewActionCell() {
        return new TableCell<>() {
            private final Button viewButton = new Button("View");

            {
                viewButton.setOnAction(e -> {
                    BookingRow row = getTableView().getItems().get(getIndex());
                    bookingDetailController.openFor(row.getId());
                    sceneRouter.navigate(Route.BOOKING_DETAIL);
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
        List<BookingListRow> results = bookingService.listBookings(!showDeliveredCheck.isSelected());
        List<BookingRow> rows = results.stream().map(BookingRow::new).toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    @FXML
    private void onSearchClicked() {
        reload();
    }

}
