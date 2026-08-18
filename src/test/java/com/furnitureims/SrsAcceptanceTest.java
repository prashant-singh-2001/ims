package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.BackupHistory;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.money.Money;
import com.furnitureims.repository.BackupHistoryRepository;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.AuthService;
import com.furnitureims.service.BackupService;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.DocumentService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.ReportService;
import com.furnitureims.service.RestoreService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.SettingsService;
import com.furnitureims.service.SetupService;
import com.furnitureims.service.StorageLocationService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Milestone M9: "a full run of the 20 acceptance tests in SRS &sect;8". Every A-item is
 * accounted for somewhere - most (A3-A5, A7, A11-A15, A18, A20) already have a direct,
 * focused test in an earlier milestone's suite and are not duplicated here; see the
 * traceability table added to {@code docs/01-requirements.md} &sect;8 for exactly which
 * class covers which item. This class holds only the items that had no existing coverage:
 * <ul>
 *   <li>A1 - setup-completion gating logic (the wizard screen flow itself is UI-only)</li>
 *   <li>A2 - login accept/reject (idle-lock state preservation is UI-only)</li>
 *   <li>A6 - advance at billing correctly reduces the dues-report balance</li>
 *   <li>A8 - profit is frozen at sale time, unaffected by a later price edit</li>
 *   <li>A9 - the invoice PDF's actual text contains the mandatory fields, not just "a PDF
 *       exists" (M6DocumentsTest only checked existence and size)</li>
 *   <li>A16 - retention prunes to the real default counts (14 daily / 12 weekly), not just
 *       the retention=1 case M8BackupTest exercised</li>
 *   <li>A17 - restore reproduces photos and invoice PDFs, not just database rows
 *       (M8BackupTest's round trip only checked the database) - the roadmap flags this as
 *       one of the two tests "most likely to fail late"</li>
 *   <li>A19 - the dashboard's backup-status tile actually turns red after 48 hours</li>
 * </ul>
 * A10 (WhatsApp deep link, live email delivery) and the Drive-upload half of A14/A15/A18
 * are manual-only, the same documented limitation M6 and M8 already accepted for live
 * SMTP/OAuth.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(SrsAcceptanceTest.TestPathsConfig.class)
class SrsAcceptanceTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-srs-acceptance-"));
        }
    }

    @Autowired private AppPaths appPaths;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private CustomerService customerService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private ReportService reportService;
    @Autowired private DocumentService documentService;
    @Autowired private SetupService setupService;
    @Autowired private AuthService authService;
    @Autowired private BackupService backupService;
    @Autowired private RestoreService restoreService;
    @Autowired private SettingsService settingsService;
    @Autowired private BackupHistoryRepository backupHistoryRepository;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpShopProfile() {
        shopProfileRepository.save(new ShopProfile("Test Furniture Shop", "12 MG Road", null, "Pune", "411001",
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", "shop@example.com", null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");
    }

    private long chairModelId(String code) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        return itemModelService.create(new ItemModel(0, code, "Oak Dining Chair", categoryId, "9403",
                new BigDecimal("18"), null, null, null, null, null, null, Money.ofRupees("5000.00"), true, null));
    }

    private Piece newPiece(long modelId, String cost) {
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        return pieceService.createOpeningStock(modelId, 1, Money.ofRupees(cost), locationId, LocalDate.now()).get(0);
    }

    // ---- A1: fresh install is unusable until setup completes -----------------------------

    @Test
    void a1_setupIsNotCompleteUntilBothShopProfileAndOwnerAccountExist() {
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM shop_profile");
        assertFalse(setupService.isSetupComplete(), "neither a shop profile nor an owner account exists yet");

        setupService.saveShopProfile(new ShopProfile("Fresh Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        assertFalse(setupService.isSetupComplete(), "a shop profile alone is not enough");

        setupService.createOwnerAccount("owner", "correct-horse-battery-staple");
        assertTrue(setupService.isSetupComplete(), "both exist now - the wizard should not reappear");
    }

    // ---- A2: wrong password is refused; correct one enters --------------------------------

    @Test
    void a2_wrongPasswordIsRefusedAndCorrectPasswordLogsIn() {
        jdbc.update("DELETE FROM app_user");
        setupService.createOwnerAccount("owner", "correct-horse-battery-staple");

        assertThrows(AuthService.AuthException.class,
                () -> authService.login("owner", "totally wrong password"));

        var user = authService.login("owner", "correct-horse-battery-staple");
        assertEquals("owner", user.username());
    }

    // ---- A6: advance reduces the dues-report balance, correctly aged ----------------------

    @Test
    void a6_advanceAtBillingReducesTheOutstandingBalanceShownInTheDuesReport() {
        long modelId = chairModelId("SRSA");
        Piece piece = newPiece(modelId, "3000.00");
        long customerId = customerService.create(new Customer(0, "Advance Test Customer", "9000000101",
                null, null, null, null, null, null, null, null, null));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now(), Money.ofRupees("2000.00"), com.furnitureims.domain.Payment.Mode.CASH,
                null, "Advance at billing");

        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();
        Money expectedBalance = invoice.grandTotal().minus(Money.ofRupees("2000.00"));
        assertEquals(expectedBalance, salesInvoiceService.balance(invoice));

        List<ReportService.CustomerDueRow> dues = reportService.customerDuesAging(customerId, null, null);
        assertEquals(1, dues.size());
        assertEquals(expectedBalance, dues.get(0).balance());
        assertEquals(ReportService.AgingBucket.DAYS_0_30, dues.get(0).bucket(),
                "an invoice dated today should be in the freshest aging bucket");
    }

    // ---- A8: profit is frozen at sale time -------------------------------------------------

    @Test
    void a8_profitDoesNotChangeWhenTheModelsDefaultPriceIsEditedAfterTheSale() {
        long modelId = chairModelId("SRSB");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerService.create(new Customer(0, "Profit Freeze Customer", "9000000102",
                null, null, null, null, null, null, null, null, null));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("4000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());

        ReportService.SalesProfitReport before = reportService.salesAndProfitReport(LocalDate.now(), LocalDate.now());
        Money profitBefore = before.summary().profit();

        // Edit the model's default selling price well after the sale - this must not
        // retroactively change what the invoice's own profit figure says.
        ItemModel model = itemModelService.findById(modelId).orElseThrow();
        itemModelService.update(new ItemModel(model.id(), model.modelCode(), model.modelName(), model.categoryId(),
                model.hsnCode(), model.gstRate(), model.lengthCm(), model.widthCm(), model.heightCm(),
                model.material(), model.finish(), model.colour(), Money.ofRupees("9999.00"), model.active(),
                model.notes()));

        ReportService.SalesProfitReport after = reportService.salesAndProfitReport(LocalDate.now(), LocalDate.now());
        assertEquals(profitBefore, after.summary().profit(),
                "profit is computed from cost_at_sale, frozen at the moment of sale (FR-SAL-09) - "
                        + "a later catalogue price edit must not move it");

        var line = salesInvoiceService.linesFor(invoiceId).get(0);
        assertEquals(Money.ofRupees("1000.00"), line.costAtSale());
    }

    // ---- A9: the invoice PDF actually carries the mandatory fields ------------------------

    @Test
    void a9_invoicePdfTextContainsGstinInvoiceNumberAndAmountInWords() throws IOException {
        long modelId = chairModelId("SRSC");
        Piece piece = newPiece(modelId, "2000.00");
        long customerId = customerService.create(new Customer(0, "PDF Content Customer", "9000000103",
                null, null, null, null, null, null, null, null, null));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        Path pdf = documentService.generateInvoicePdf(invoiceId);

        StringBuilder text = new StringBuilder();
        try (PdfReader reader = new PdfReader(pdf.toString())) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
        }
        String content = text.toString();

        assertTrue(content.contains("27AAAAA0000A1Z5"), "shop GSTIN must appear on the invoice");
        assertTrue(content.contains(invoice.invoiceNo()), "invoice number must appear on the invoice");
        assertTrue(content.contains("PDF Content Customer"), "customer name must appear on the invoice");
        assertTrue(content.contains("9403"), "the piece's HSN code must appear on the invoice");
        assertTrue(content.toLowerCase().contains("rupees"),
                "the grand total must be spelled out in words (FR-DOC-01)");
    }

    // ---- A16: retention prunes to the real default counts ---------------------------------

    @Test
    void a16_retentionPrunesToFourteenDailyAndTwelveWeeklyByDefault() {
        // Default retention (14 daily / 12 weekly) is untouched by this test - seed more
        // than that many already-SUCCESS, local-only, historical entries directly (running
        // the real pipeline 16+14 times just to test the prune step would be needlessly
        // slow), then trigger one real backup, whose own pruneRetention() step must cut
        // both lists down to the defaults.
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 16; i++) {
            backupHistoryRepository.create(new BackupHistory(0, BackupHistory.BackupType.DAILY,
                    now.minusDays(i + 1), now.minusDays(i + 1), BackupHistory.Status.SUCCESS,
                    "seed-daily-" + i + ".zip.enc", 100L, "sha", null, null, null));
        }
        for (int i = 0; i < 14; i++) {
            backupHistoryRepository.create(new BackupHistory(0, BackupHistory.BackupType.WEEKLY,
                    now.minusWeeks(i + 1), now.minusWeeks(i + 1), BackupHistory.Status.SUCCESS,
                    "seed-weekly-" + i + ".zip.enc", 100L, "sha", null, null, null));
        }

        backupService.runBackup(BackupHistory.BackupType.MANUAL, "acceptance-test-password".toCharArray());

        assertEquals(14, backupHistoryRepository
                .findByBackupTypeAndStatusOrderedByStartedDesc(BackupHistory.BackupType.DAILY,
                        BackupHistory.Status.SUCCESS).size(),
                "retention should keep exactly the default 14 daily archives");
        assertEquals(12, backupHistoryRepository
                .findByBackupTypeAndStatusOrderedByStartedDesc(BackupHistory.BackupType.WEEKLY,
                        BackupHistory.Status.SUCCESS).size(),
                "retention should keep exactly the default 12 weekly archives");
    }

    // ---- A17: restore reproduces photos and invoice PDFs, not just the database -----------

    @Test
    void a17_restoringAnArchiveReproducesPhotosAndInvoicePdfsNotJustTheDatabase() throws IOException {
        Files.createDirectories(appPaths.photos());
        Files.createDirectories(appPaths.invoices());
        Path photo = appPaths.photos().resolve("sofa-model-1.jpg");
        Path invoicePdf = appPaths.invoices().resolve("INV-TEST-0001.pdf");
        Files.writeString(photo, "pretend-jpeg-bytes-for-a-sofa-photo");
        Files.writeString(invoicePdf, "pretend-pdf-bytes-for-an-invoice");

        char[] password = "acceptance-test-password".toCharArray();
        BackupService.BackupOutcome backup = backupService.runBackup(BackupHistory.BackupType.MANUAL, password);

        // Simulate the disaster this whole feature exists for: the photo and the PDF are
        // gone from live disk, exactly like a dead hard drive would produce.
        Files.delete(photo);
        Files.delete(invoicePdf);
        assertFalse(Files.exists(photo));
        assertFalse(Files.exists(invoicePdf));

        RestoreService.RestorePreview preview = restoreService.prepareRestore(backup.historyId(), password);
        restoreService.performRestore(preview);

        assertTrue(Files.exists(photo), "the photo must be back after restore, not just the database");
        assertEquals("pretend-jpeg-bytes-for-a-sofa-photo", Files.readString(photo));
        assertTrue(Files.exists(invoicePdf), "the invoice PDF must be back after restore, not just the database");
        assertEquals("pretend-pdf-bytes-for-an-invoice", Files.readString(invoicePdf));
    }

    // ---- A19: no backup for 48 hours produces a visible warning ---------------------------

    private static volatile boolean fxToolkitStarted = false;

    private static synchronized void ensureFxToolkitStarted() throws InterruptedException {
        if (fxToolkitStarted) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // Another test class in this same forked JVM already started the toolkit -
            // JavaFX allows only one Platform.startup() call per process.
            fxToolkitStarted = true;
            return;
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("JavaFX toolkit did not start in time");
        }
        fxToolkitStarted = true;
    }

    @Test
    void a19_dashboardShowsARedWarningAfterFortyEightHoursWithNoSuccessfulBackup() throws Exception {
        ensureFxToolkitStarted();

        backupHistoryRepository.create(new BackupHistory(0, BackupHistory.BackupType.DAILY,
                LocalDateTime.now().minusHours(60), LocalDateTime.now().minusHours(60),
                BackupHistory.Status.SUCCESS, "stale.zip.enc", 100L, "sha", null, null, null));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<List<String>> labelStyleClasses = new AtomicReference<>();
        AtomicReference<String> labelText = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                URL location = getClass().getResource("/fxml/shell/dashboard.fxml");
                FXMLLoader loader = new FXMLLoader(location);
                loader.setControllerFactory(applicationContext::getBean);
                loader.load();

                // fx:id only wires @FXML field injection - it does not also set the node's
                // CSS id, so Parent.lookup("#backupStatusLabel") cannot find it. Reading the
                // controller's own private field via reflection is the practical way to
                // inspect resulting UI state in this project, which - per FxmlLoadSmokeTest's
                // own javadoc - already treats "load the real FXML on the real FX thread" as
                // the reliable alternative to driving the GUI with simulated input.
                Object controller = loader.getController();
                var field = controller.getClass().getDeclaredField("backupStatusLabel");
                field.setAccessible(true);
                Label backupStatusLabel = (Label) field.get(controller);
                labelStyleClasses.set(List.copyOf(backupStatusLabel.getStyleClass()));
                labelText.set(backupStatusLabel.getText());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out loading the dashboard");
        }
        if (error.get() != null) {
            throw new AssertionError("Failed to load dashboard.fxml", error.get());
        }

        // Asserts the style *class* rather than a colour value: since M10 the status colour
        // comes from the theme via .backup-overdue, so pinning a hex here would break on
        // every palette change while testing nothing about the 48-hour rule itself.
        assertTrue(labelStyleClasses.get().contains("backup-overdue"),
                "60 hours since the last successful backup is well past the 48-hour warning "
                        + "threshold - the tile must be marked overdue, not ok or warn. Classes were: "
                        + labelStyleClasses.get());
        assertTrue(labelText.get().contains("SUCCESS"), "the status text should still say what the last "
                + "attempt's own outcome was");
    }
}
