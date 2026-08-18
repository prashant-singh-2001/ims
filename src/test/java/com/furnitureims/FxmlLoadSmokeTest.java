package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.PurchaseBillService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.service.SupplierService;
import com.furnitureims.ui.catalogue.PieceDetailController;
import com.furnitureims.ui.purchase.PurchaseReturnController;
import com.furnitureims.ui.sales.InvoiceDetailController;
import com.furnitureims.ui.sales.SalesReturnScreenController;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loads every M2 FXML screen through the same FXMLLoader + Spring controller factory
 * pattern {@code SceneRouter} uses, on the real JavaFX Application Thread. This is the
 * only reliable way to catch an fx:id that doesn't match an {@code @FXML} field, a wrong
 * control type, or a broken controller wire-up in this environment - the compiler cannot
 * see FXML, and driving the actual GUI with simulated input is not reliable here (no way
 * to force real OS focus onto a specific window without risking stray input landing
 * elsewhere on the desktop).
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(FxmlLoadSmokeTest.TestPathsConfig.class)
class FxmlLoadSmokeTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-fxml-test-"));
        }
    }

    private static volatile boolean fxToolkitStarted = false;

    private static synchronized void ensureFxToolkitStarted() throws InterruptedException {
        if (fxToolkitStarted) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("JavaFX toolkit did not start in time");
        }
        // Same base theme the real app applies in FurnitureImsFxApp.applyBaseTheme(). Without
        // this the whole suite would keep validating every screen against JavaFX's stock
        // Modena stylesheet, and so could not catch a theme regression even in principle.
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        fxToolkitStarted = true;
    }

    @Autowired private ApplicationContext applicationContext;
    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private PieceDetailController pieceDetailController;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private SupplierService supplierService;
    @Autowired private PurchaseBillService purchaseBillService;
    @Autowired private PurchaseReturnController purchaseReturnController;
    @Autowired private CustomerService customerService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private InvoiceDetailController invoiceDetailController;
    @Autowired private SalesReturnScreenController salesReturnScreenController;
    @Autowired private JdbcTemplate jdbc;

    /** Screens with no prerequisite state - loadable straight from a fresh database. */
    @Test
    void standaloneScreensLoadWithoutError() throws Exception {
        ensureFxToolkitStarted();

        List<String> screens = List.of(
                // The two entry-point screens, previously untested. setup-wizard.fxml
                // transitively loads all four step fragments in its own initialize(), and
                // SetupWizardController.loadStep already names the offending file in its
                // exception - so the steps need no separate entries here, and listing them
                // standalone would only double-load their singleton controllers.
                "/fxml/login/login.fxml",
                "/fxml/setup/setup-wizard.fxml",

                "/fxml/shell/dashboard.fxml",
                "/fxml/catalogue/categories-locations.fxml",
                "/fxml/catalogue/item-model-list.fxml",
                "/fxml/catalogue/item-model-editor.fxml",
                "/fxml/catalogue/piece-register.fxml",
                "/fxml/catalogue/opening-stock-entry.fxml",
                "/fxml/purchase/supplier-list.fxml",
                "/fxml/purchase/supplier-editor.fxml",
                "/fxml/purchase/purchase-bill-list.fxml",
                "/fxml/purchase/purchase-bill-entry.fxml",
                "/fxml/sales/new-sale.fxml",
                "/fxml/sales/invoice-list.fxml",
                "/fxml/payment/customer-receipt.fxml",
                "/fxml/payment/supplier-payment.fxml",
                "/fxml/payment/payment-list.fxml",
                "/fxml/settings/settings.fxml",
                "/fxml/settings/audit-log.fxml",
                "/fxml/reports/stock-report.fxml",
                "/fxml/reports/sales-profit-report.fxml",
                "/fxml/reports/dues-report.fxml",
                "/fxml/backup/backup-settings.fxml"
        );
        for (String screen : screens) {
            loadOnFxThread(screen);
        }
    }

    /** Piece detail requires a real piece id to be set via openFor() first, exactly as
     *  PieceRegisterController does before navigating to it. */
    @Test
    void pieceDetailScreenLoadsWithoutError() throws Exception {
        ensureFxToolkitStarted();

        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        long modelId = itemModelService.create(new com.furnitureims.domain.ItemModel(
                0, "SMOKE", "Smoke Test Chair", categoryId, "9403", new BigDecimal("18"),
                null, null, null, null, null, null, null, true, null));
        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("999.00"), locationId, LocalDate.now()).get(0);

        pieceDetailController.openFor(piece.id());
        loadOnFxThread("/fxml/catalogue/piece-detail.fxml");
    }

    /** Purchase return requires a real RECEIVED bill's id to be set via openFor() first,
     *  exactly as PurchaseBillListController does before navigating to it. */
    @Test
    void purchaseReturnScreenLoadsWithoutError() throws Exception {
        ensureFxToolkitStarted();

        shopProfileRepository.save(new ShopProfile("Smoke Test Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");

        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        long modelId = itemModelService.create(new com.furnitureims.domain.ItemModel(
                0, "SMOKEPB", "Smoke Test Chair", categoryId, "9403", new BigDecimal("18"),
                null, null, null, null, null, null, null, true, null));
        long supplierId = supplierService.create(new Supplier(0, "Smoke Test Supplier", null, null, null,
                null, null, "Maharashtra", "27", null, null, null, Money.ZERO, true, null));

        long billId = purchaseBillService.saveDraft(null, supplierId, "SMOKE-BILL-1", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, List.of(
                        new PurchaseBillService.LineInput(modelId, 1, Money.ofRupees("500.00"), Money.ZERO,
                                new BigDecimal("18"))));
        purchaseBillService.confirmReceipt(billId);

        purchaseReturnController.openFor(billId);
        loadOnFxThread("/fxml/purchase/purchase-return.fxml");
    }

    /** Invoice detail and sales return both require a real ACTIVE invoice id to be set
     *  via openFor() first, exactly as InvoiceListController/InvoiceDetailController do
     *  before navigating to them. */
    @Test
    void invoiceDetailAndSalesReturnScreensLoadWithoutError() throws Exception {
        ensureFxToolkitStarted();

        shopProfileRepository.save(new ShopProfile("Smoke Test Shop 2", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");

        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        long modelId = itemModelService.create(new com.furnitureims.domain.ItemModel(
                0, "SMOKESL", "Smoke Test Chair", categoryId, "9403", new BigDecimal("18"),
                null, null, null, null, null, null, null, true, null));
        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("500.00"), locationId, LocalDate.now()).get(0);
        long customerId = customerService.create(new Customer(0, "Smoke Test Customer", "9000000099",
                null, null, null, null, null, null, null, null, null));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("1000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());

        invoiceDetailController.openFor(invoiceId);
        loadOnFxThread("/fxml/sales/invoice-detail.fxml");

        salesReturnScreenController.openFor(invoiceId);
        loadOnFxThread("/fxml/sales/sales-return.fxml");
    }

    /**
     * Loads the FXML, then puts its root in a throwaway {@link Scene} with the real
     * stylesheet attached and forces a CSS pass and one layout pass. Loading alone only
     * proves the FXML <em>parses</em>; a restyle breaks things that surface later than
     * that - an unparseable {@code -fx-} value, a bad looked-up colour, or a cell factory
     * that NPEs the first time a table actually lays out its rows.
     */
    private void loadOnFxThread(String classpathFxml) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Parent> result = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                URL location = getClass().getResource(classpathFxml);
                FXMLLoader loader = new FXMLLoader(location);
                loader.setControllerFactory(applicationContext::getBean);
                Parent root = loader.load();

                Scene scene = new Scene(root, 1280, 800);
                scene.getStylesheets().add(
                        FxmlLoadSmokeTest.class.getResource("/css/app.css").toExternalForm());
                root.applyCss();
                root.layout();

                result.set(root);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out loading " + classpathFxml);
        }
        if (error.get() != null) {
            throw new AssertionError("Failed to load " + classpathFxml, error.get());
        }
        assertNotNull(result.get(), classpathFxml + " loaded a null root");
    }
}
