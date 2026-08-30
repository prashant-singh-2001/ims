package com.piecetrack.ui.shell;

import com.piecetrack.domain.AppUser;
import com.piecetrack.domain.BackupHistory;
import com.piecetrack.repository.BackupHistoryRepository;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.service.AppSession;
import com.piecetrack.service.ReportService;
import com.piecetrack.ui.Icons;
import com.piecetrack.ui.Route;
import com.piecetrack.ui.SceneRouter;
import com.piecetrack.ui.purchase.PurchaseBillEntryController;
import com.piecetrack.ui.reports.DuesReportController;
import com.piecetrack.ui.reports.SalesProfitReportController;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The real dashboard (FR-RPT-05, FR-BAK-11) - replaces M1's placeholder now that reports
 * exist to fill it in (docs/03-screens.md section 3). Tiles double as drill-through
 * shortcuts into the report they summarize, per that section's own description of them.
 * <p>
 * Backup status shows the last backup *attempt*'s own outcome as text (honest - it says
 * FAILED if the last one failed) but colors green/amber/red based on how long it has been
 * since the last backup that actually *succeeded* (or is waiting only on an upload,
 * FR-BAK-12's UPLOAD_PENDING) - these are deliberately two different signals per FR-BAK-11.
 */
@Component
public class DashboardController {

    static final String STATUS_OK = "backup-ok";
    static final String STATUS_WARN = "backup-warn";
    static final String STATUS_OVERDUE = "backup-overdue";
    static final String STATUS_PENDING = "backup-pending";

    private final AppSession appSession;
    private final ShopProfileRepository shopProfileRepository;
    private final ReportService reportService;
    private final BackupHistoryRepository backupHistoryRepository;
    private final PurchaseBillEntryController purchaseBillEntryController;
    private final SalesProfitReportController salesProfitReportController;
    private final DuesReportController duesReportController;
    private final SceneRouter sceneRouter;

    @FXML private Label welcomeLabel;

    @FXML private Label todaySalesLabel;
    @FXML private Label monthSalesLabel;
    @FXML private Label stockValueLabel;
    @FXML private VBox piecesInStockTile;
    @FXML private Label piecesInStockLabel;
    @FXML private Label receivableLabel;
    @FXML private Label payableLabel;
    @FXML private VBox backupStatusTile;
    @FXML private Label backupStatusLabel;

    public DashboardController(AppSession appSession, ShopProfileRepository shopProfileRepository,
                                ReportService reportService,
                                BackupHistoryRepository backupHistoryRepository,
                                PurchaseBillEntryController purchaseBillEntryController,
                                SalesProfitReportController salesProfitReportController,
                                DuesReportController duesReportController, SceneRouter sceneRouter) {
        this.appSession = appSession;
        this.shopProfileRepository = shopProfileRepository;
        this.reportService = reportService;
        this.backupHistoryRepository = backupHistoryRepository;
        this.purchaseBillEntryController = purchaseBillEntryController;
        this.salesProfitReportController = salesProfitReportController;
        this.duesReportController = duesReportController;
        this.sceneRouter = sceneRouter;
    }

    @FXML
    private void initialize() {
        AppUser user = appSession.currentUser();
        String shopName = shopProfileRepository.find().map(profile -> profile.shopName()).orElse("your shop");
        String username = user == null ? "" : user.username();
        welcomeLabel.setText("Welcome back, " + username + " - " + shopName);

        ReportService.DashboardSummary summary = reportService.dashboardSummary();
        todaySalesLabel.setText(summary.todaySales().toDisplayString() + " (" + summary.todayInvoiceCount()
                + " invoice" + (summary.todayInvoiceCount() == 1 ? "" : "s") + ")");
        monthSalesLabel.setText(summary.monthSales().toDisplayString() + " | profit "
                + summary.monthProfit().toDisplayString());
        stockValueLabel.setText(summary.stockValue().toDisplayString());
        piecesInStockLabel.setText(summary.piecesInStock() + " pieces (" + summary.piecesAged90Plus()
                + " aged 90+ days)");
        applyStockAgingStyle(summary.piecesAged90Plus());
        receivableLabel.setText(summary.receivable().toDisplayString() + " (" + summary.overdueInvoiceCount()
                + " invoice" + (summary.overdueInvoiceCount() == 1 ? "" : "s") + " outstanding)");
        payableLabel.setText(summary.payable().toDisplayString());
        updateBackupStatus();
    }

    private void updateBackupStatus() {
        List<BackupHistory> all = backupHistoryRepository.findAllOrderedByStartedDesc();
        if (all.isEmpty()) {
            backupStatusLabel.setText("No backups yet");
            applyBackupStatusStyle(STATUS_OVERDUE);
            return;
        }

        BackupHistory lastAttempt = all.get(0);
        backupStatusLabel.setText(lastAttempt.status() + " - " + lastAttempt.startedAt().toString().replace('T', ' '));

        Optional<BackupHistory> lastGood = all.stream()
                .filter(b -> b.status() == BackupHistory.Status.SUCCESS
                        || b.status() == BackupHistory.Status.UPLOAD_PENDING)
                .findFirst();
        if (lastGood.isEmpty()) {
            applyBackupStatusStyle(STATUS_OVERDUE);
            return;
        }

        long hoursSinceGood = Duration.between(lastGood.get().startedAt(), LocalDateTime.now()).toHours();
        if (hoursSinceGood <= 24) {
            // A fresh archive that hasn't uploaded yet (FR-BAK-12: offline queueing, or the
            // upload simply hasn't run yet) reads as "in progress", not "confirmed good" -
            // distinct from STATUS_OK even though both currently sit inside the 24h window.
            boolean stillUploading = lastAttempt.status() == BackupHistory.Status.UPLOAD_PENDING;
            applyBackupStatusStyle(stillUploading ? STATUS_PENDING : STATUS_OK);
        } else if (hoursSinceGood <= 48) {
            applyBackupStatusStyle(STATUS_WARN);
        } else {
            applyBackupStatusStyle(STATUS_OVERDUE);
        }
    }

    /** Style classes rather than the hardcoded hex colours this used before M10: they follow
     *  the theme, and a test can assert the *meaning* ("overdue") instead of a colour value
     *  that changes whenever the palette does. The icon alongside them signals the same
     *  state by shape and colour, so it survives colour-blindness too, and the tile's own
     *  top edge (app.css's ".metric-tile-*") repeats the same colour a third way. */
    private void applyBackupStatusStyle(String statusClass) {
        backupStatusLabel.getStyleClass().setAll("metric-tile-label", statusClass);
        backupStatusLabel.setGraphic(switch (statusClass) {
            case STATUS_OK -> Icons.ok();
            case STATUS_WARN -> Icons.warning();
            case STATUS_PENDING -> Icons.pending();
            default -> Icons.danger();
        });
        backupStatusTile.getStyleClass().setAll("metric-tile",
                "metric-tile-" + statusClass.substring("backup-".length()));
    }

    /** Inventory-first (M10): the Pieces in Stock tile becomes an attention-seeking
     *  {@code .alert-surface} - the same class the stock report uses for its aged-stock
     *  callout - whenever there is aged stock to look at, instead of always reading as a
     *  plain, equally-weighted KPI. */
    private void applyStockAgingStyle(int agedCount) {
        piecesInStockTile.getStyleClass().setAll(agedCount > 0 ? "alert-surface" : "metric-tile");
    }

    @FXML
    private void onTodaySalesTileClicked() {
        LocalDate today = LocalDate.now();
        salesProfitReportController.openWithDateRange(today, today);
        sceneRouter.navigate(Route.SALES_PROFIT_REPORT);
    }

    @FXML
    private void onMonthSalesTileClicked() {
        LocalDate today = LocalDate.now();
        salesProfitReportController.openWithDateRange(today.withDayOfMonth(1), today);
        sceneRouter.navigate(Route.SALES_PROFIT_REPORT);
    }

    @FXML
    private void onStockValueTileClicked() {
        sceneRouter.navigate(Route.STOCK_REPORT);
    }

    @FXML
    private void onReceivableTileClicked() {
        duesReportController.openWithTab(0);
        sceneRouter.navigate(Route.DUES_REPORT);
    }

    @FXML
    private void onPayableTileClicked() {
        duesReportController.openWithTab(1);
        sceneRouter.navigate(Route.DUES_REPORT);
    }

    @FXML
    private void onBackupStatusTileClicked() {
        sceneRouter.navigate(Route.BACKUP_SETTINGS);
    }

    @FXML
    private void onNewSaleClicked() {
        sceneRouter.navigate(Route.NEW_SALE);
    }

    @FXML
    private void onInvoicesClicked() {
        sceneRouter.navigate(Route.INVOICE_LIST);
    }

    /** Same default range as {@link #onMonthSalesTileClicked} - this button is the general
     *  "take me to the sales report" quick action, not tied to a specific tile's own range,
     *  and month-to-date is the most useful default when nothing more specific was clicked. */
    @FXML
    private void onSalesReportClicked() {
        LocalDate today = LocalDate.now();
        salesProfitReportController.openWithDateRange(today.withDayOfMonth(1), today);
        sceneRouter.navigate(Route.SALES_PROFIT_REPORT);
    }

    @FXML
    private void onNewPurchaseBillClicked() {
        purchaseBillEntryController.openForNew();
        sceneRouter.navigate(Route.PURCHASE_BILL_ENTRY);
    }

    @FXML
    private void onPaymentsClicked() {
        sceneRouter.navigate(Route.PAYMENT_LIST);
    }
}
