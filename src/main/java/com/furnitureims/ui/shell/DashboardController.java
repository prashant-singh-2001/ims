package com.furnitureims.ui.shell;

import com.furnitureims.domain.AppUser;
import com.furnitureims.domain.BackupHistory;
import com.furnitureims.repository.BackupHistoryRepository;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.AppSession;
import com.furnitureims.service.ReportService;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.ui.login.IdleLockManager;
import com.furnitureims.ui.purchase.PurchaseBillEntryController;
import com.furnitureims.ui.reports.DuesReportController;
import com.furnitureims.ui.reports.SalesProfitReportController;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
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

    private final AppSession appSession;
    private final ShopProfileRepository shopProfileRepository;
    private final IdleLockManager idleLockManager;
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
    @FXML private Label piecesInStockLabel;
    @FXML private Label receivableLabel;
    @FXML private Label payableLabel;
    @FXML private Label backupStatusLabel;

    public DashboardController(AppSession appSession, ShopProfileRepository shopProfileRepository,
                                IdleLockManager idleLockManager, ReportService reportService,
                                BackupHistoryRepository backupHistoryRepository,
                                PurchaseBillEntryController purchaseBillEntryController,
                                SalesProfitReportController salesProfitReportController,
                                DuesReportController duesReportController, SceneRouter sceneRouter) {
        this.appSession = appSession;
        this.shopProfileRepository = shopProfileRepository;
        this.idleLockManager = idleLockManager;
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
        receivableLabel.setText(summary.receivable().toDisplayString() + " (" + summary.overdueInvoiceCount()
                + " invoice" + (summary.overdueInvoiceCount() == 1 ? "" : "s") + " outstanding)");
        payableLabel.setText(summary.payable().toDisplayString());
        updateBackupStatus();
    }

    private void updateBackupStatus() {
        List<BackupHistory> all = backupHistoryRepository.findAllOrderedByStartedDesc();
        if (all.isEmpty()) {
            backupStatusLabel.setText("No backups yet");
            applyBackupStatusColor("#b3261e");
            return;
        }

        BackupHistory lastAttempt = all.get(0);
        backupStatusLabel.setText(lastAttempt.status() + " - " + lastAttempt.startedAt().toString().replace('T', ' '));

        Optional<BackupHistory> lastGood = all.stream()
                .filter(b -> b.status() == BackupHistory.Status.SUCCESS
                        || b.status() == BackupHistory.Status.UPLOAD_PENDING)
                .findFirst();
        if (lastGood.isEmpty()) {
            applyBackupStatusColor("#b3261e");
            return;
        }

        long hoursSinceGood = Duration.between(lastGood.get().startedAt(), LocalDateTime.now()).toHours();
        if (hoursSinceGood <= 24) {
            applyBackupStatusColor("#1a7f37");
        } else if (hoursSinceGood <= 48) {
            applyBackupStatusColor("#a16a00");
        } else {
            applyBackupStatusColor("#b3261e");
        }
    }

    private void applyBackupStatusColor(String hex) {
        backupStatusLabel.setStyle("-fx-text-fill: " + hex + "; -fx-font-weight: bold;");
    }

    @FXML
    private void onLockNowClicked() {
        idleLockManager.lockNow();
    }

    @FXML
    private void onTodaySalesTileClicked() {
        LocalDate today = LocalDate.now();
        salesProfitReportController.openWithDateRange(today, today);
        sceneRouter.show("/fxml/reports/sales-profit-report.fxml");
    }

    @FXML
    private void onMonthSalesTileClicked() {
        LocalDate today = LocalDate.now();
        salesProfitReportController.openWithDateRange(today.withDayOfMonth(1), today);
        sceneRouter.show("/fxml/reports/sales-profit-report.fxml");
    }

    @FXML
    private void onStockValueTileClicked() {
        sceneRouter.show("/fxml/reports/stock-report.fxml");
    }

    @FXML
    private void onReceivableTileClicked() {
        duesReportController.openWithTab(0);
        sceneRouter.show("/fxml/reports/dues-report.fxml");
    }

    @FXML
    private void onPayableTileClicked() {
        duesReportController.openWithTab(1);
        sceneRouter.show("/fxml/reports/dues-report.fxml");
    }

    @FXML
    private void onBackupStatusTileClicked() {
        sceneRouter.show("/fxml/backup/backup-settings.fxml");
    }

    @FXML
    private void onNewSaleClicked() {
        sceneRouter.show("/fxml/sales/new-sale.fxml");
    }

    @FXML
    private void onNewPurchaseBillClicked() {
        purchaseBillEntryController.openForNew();
        sceneRouter.show("/fxml/purchase/purchase-bill-entry.fxml");
    }

    @FXML
    private void onPaymentsClicked() {
        sceneRouter.show("/fxml/payment/payment-list.fxml");
    }

    @FXML
    private void onBackupNowClicked() {
        sceneRouter.show("/fxml/backup/backup-settings.fxml");
    }

    @FXML
    private void onStockReportClicked() {
        sceneRouter.show("/fxml/reports/stock-report.fxml");
    }

    @FXML
    private void onSalesProfitReportClicked() {
        sceneRouter.show("/fxml/reports/sales-profit-report.fxml");
    }

    @FXML
    private void onDuesReportClicked() {
        sceneRouter.show("/fxml/reports/dues-report.fxml");
    }

    @FXML
    private void onItemModelsClicked() {
        sceneRouter.show("/fxml/catalogue/item-model-list.fxml");
    }

    @FXML
    private void onPieceRegisterClicked() {
        sceneRouter.show("/fxml/catalogue/piece-register.fxml");
    }

    @FXML
    private void onCategoriesLocationsClicked() {
        sceneRouter.show("/fxml/catalogue/categories-locations.fxml");
    }

    @FXML
    private void onSuppliersClicked() {
        sceneRouter.show("/fxml/purchase/supplier-list.fxml");
    }

    @FXML
    private void onPurchaseBillsClicked() {
        sceneRouter.show("/fxml/purchase/purchase-bill-list.fxml");
    }

    @FXML
    private void onInvoicesClicked() {
        sceneRouter.show("/fxml/sales/invoice-list.fxml");
    }

    @FXML
    private void onSettingsClicked() {
        sceneRouter.show("/fxml/settings/settings.fxml");
    }
}
