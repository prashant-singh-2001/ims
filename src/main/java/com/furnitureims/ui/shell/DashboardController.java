package com.furnitureims.ui.shell;

import com.furnitureims.domain.AppUser;
import com.furnitureims.domain.BackupHistory;
import com.furnitureims.repository.BackupHistoryRepository;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.AppSession;
import com.furnitureims.service.ReportService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.ui.purchase.PurchaseBillEntryController;
import com.furnitureims.ui.reports.DuesReportController;
import com.furnitureims.ui.reports.SalesProfitReportController;
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
            applyBackupStatusStyle(STATUS_OK);
        } else if (hoursSinceGood <= 48) {
            applyBackupStatusStyle(STATUS_WARN);
        } else {
            applyBackupStatusStyle(STATUS_OVERDUE);
        }
    }

    /** Style classes rather than the hardcoded hex colours this used before M10: they follow
     *  the theme, and a test can assert the *meaning* ("overdue") instead of a colour value
     *  that changes whenever the palette does. */
    private void applyBackupStatusStyle(String statusClass) {
        backupStatusLabel.getStyleClass().setAll("metric-tile-label", statusClass);
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
    private void onNewPurchaseBillClicked() {
        purchaseBillEntryController.openForNew();
        sceneRouter.navigate(Route.PURCHASE_BILL_ENTRY);
    }

    @FXML
    private void onPaymentsClicked() {
        sceneRouter.navigate(Route.PAYMENT_LIST);
    }
}
