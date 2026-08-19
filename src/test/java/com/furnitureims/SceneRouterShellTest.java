package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Piece;
import com.furnitureims.money.Money;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.Route;
import com.furnitureims.ui.SceneRouter;
import com.furnitureims.ui.catalogue.PieceDetailController;
import com.furnitureims.ui.sales.NewSaleController;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the real {@link SceneRouter#attachTo} / {@link SceneRouter#navigate} path (M10)
 * on the real JavaFX Application Thread - {@code FxmlLoadSmokeTest} deliberately does not:
 * it loads each FXML through its own raw {@code FXMLLoader} to test the FXML in isolation,
 * bypassing {@code SceneRouter} (and therefore the shell) entirely. Without this class nothing
 * in the suite would ever call {@code attachTo}/{@code navigate} at all, so a broken shell -
 * chrome not toggling, the Spring bean graph failing to resolve {@code NavBar}/{@code TopBar} -
 * could ship with every other test still green.
 * <p>
 * Reads {@code SceneRouter}'s private shell fields via reflection rather than growing its
 * public API for test convenience alone - the same choice already made in
 * {@code SrsAcceptanceTest.a19_*} for the same reason.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(SceneRouterShellTest.TestPathsConfig.class)
class SceneRouterShellTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-shell-test-"));
        }
    }

    @Autowired private SceneRouter sceneRouter;
    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private PieceDetailController pieceDetailController;
    @Autowired private NewSaleController newSaleController;

    private static volatile boolean fxToolkitStarted = false;

    private static synchronized void ensureFxToolkitStarted() throws InterruptedException {
        if (fxToolkitStarted) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            fxToolkitStarted = true;
            return;
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("JavaFX toolkit did not start in time");
        }
        fxToolkitStarted = true;
    }

    @Test
    void shellChromeIsVisibleOnADashboardRouteAndHiddenOnLogin() throws Exception {
        ensureFxToolkitStarted();

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> shellVisibleOnDashboard = new AtomicReference<>();
        AtomicReference<Boolean> shellVisibleOnLogin = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                sceneRouter.attachTo(new Stage());

                sceneRouter.navigate(Route.DASHBOARD);
                shellVisibleOnDashboard.set(isChromeVisible());

                sceneRouter.navigate(Route.LOGIN);
                shellVisibleOnLogin.set(isChromeVisible());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out driving SceneRouter on the FX thread");
        }
        if (error.get() != null) {
            throw new AssertionError("Shell navigation failed", error.get());
        }

        assertTrue(shellVisibleOnDashboard.get(),
                "the sidebar and top bar must be visible on a Chrome.SHELL route like the dashboard");
        assertFalse(shellVisibleOnLogin.get(),
                "the sidebar and top bar must be hidden on a Chrome.NONE route like login - "
                        + "showing navigation the owner cannot use yet would be worse than showing nothing");
    }

    @Test
    void navBarHighlightsTheActiveSectionAndFollowsDrillThroughToADetailScreen() throws Exception {
        ensureFxToolkitStarted();

        // PIECE_DETAIL's controller requires openFor(id) before its initialize() runs (it
        // looks the piece up immediately) - same seed shape FxmlLoadSmokeTest already uses
        // for the same screen.
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        long modelId = itemModelService.create(new com.furnitureims.domain.ItemModel(
                0, "SHELLT", "Shell Test Chair", categoryId, "9403", new BigDecimal("18"),
                null, null, null, null, null, null, null, true, null));
        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("999.00"), locationId, LocalDate.now()).get(0);
        pieceDetailController.openFor(piece.id());

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> stockActiveOnPieceRegister = new AtomicReference<>();
        AtomicReference<Boolean> stockActiveOnPieceDetail = new AtomicReference<>();
        AtomicReference<Boolean> salesActiveOnPieceDetail = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                sceneRouter.attachTo(new Stage());

                sceneRouter.navigate(Route.PIECE_REGISTER);
                stockActiveOnPieceRegister.set(isSectionButtonActive("Stock"));

                // PIECE_DETAIL's own section is STOCK (Route.java) - drilling into a specific
                // piece must keep the Stock nav item lit rather than clearing every highlight,
                // which is what makes the sidebar still answer "where am I" from inside a
                // detail screen, not just from a top-level list.
                sceneRouter.navigate(Route.PIECE_DETAIL);
                stockActiveOnPieceDetail.set(isSectionButtonActive("Stock"));
                salesActiveOnPieceDetail.set(isSectionButtonActive("Sales"));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out driving SceneRouter on the FX thread");
        }
        if (error.get() != null) {
            throw new AssertionError("Nav highlight check failed", error.get());
        }

        assertTrue(stockActiveOnPieceRegister.get(), "Stock should be highlighted on the piece register");
        assertTrue(stockActiveOnPieceDetail.get(), "Stock should stay highlighted while drilled into a piece");
        assertFalse(salesActiveOnPieceDetail.get(), "Sales must not also be highlighted at the same time");
    }

    @Test
    void topBarTitleReflectsTheStaticRouteTitle() throws Exception {
        ensureFxToolkitStarted();

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> title = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                sceneRouter.attachTo(new Stage());
                sceneRouter.navigate(Route.PIECE_REGISTER);
                title.set(topBarTitleLabel().getText());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out driving SceneRouter on the FX thread");
        }
        if (error.get() != null) {
            throw new AssertionError("Top bar title check failed", error.get());
        }

        assertEquals(Route.PIECE_REGISTER.title(), title.get());
    }

    @Test
    void newSaleReportsUnsavedWorkOnceALineIsAddedAndClearsItAfterReset() throws Exception {
        ensureFxToolkitStarted();

        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        long modelId = itemModelService.create(new com.furnitureims.domain.ItemModel(
                0, "UNSVDWK", "Unsaved Work Test Chair", categoryId, "9403", new BigDecimal("18"),
                null, null, null, null, null, null, null, true, null));
        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("999.00"), locationId, LocalDate.now()).get(0);

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> unsavedBeforeAdding = new AtomicReference<>();
        AtomicReference<Boolean> unsavedAfterAdding = new AtomicReference<>();
        AtomicReference<Boolean> unsavedAfterReset = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                sceneRouter.attachTo(new Stage());
                sceneRouter.navigate(Route.NEW_SALE);

                unsavedBeforeAdding.set(newSaleController.hasUnsavedWork());

                fieldValue(newSaleController, "tagField", TextField.class).setText(piece.tag());
                invokePrivateNoArg(newSaleController, "onAddByTagClicked");

                unsavedAfterAdding.set(newSaleController.hasUnsavedWork());

                // NewSaleController is a Spring singleton shared with every other test in this
                // class - leaving it reporting unsaved work here would make the next test's
                // first sceneRouter.navigate() hang forever on a real confirmation Alert that
                // nothing in the test can click. resetForNewSale() is exactly what onSaveClicked
                // runs after a real save, so this also doubles as the "clears it" half of this
                // test's own name, which the first draft asserted in name only.
                invokePrivateNoArg(newSaleController, "resetForNewSale");
                unsavedAfterReset.set(newSaleController.hasUnsavedWork());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out driving NewSaleController on the FX thread");
        }
        if (error.get() != null) {
            throw new AssertionError("Unsaved-work check failed", error.get());
        }

        assertFalse(unsavedBeforeAdding.get(), "a freshly opened New Sale screen has no unsaved work");
        assertTrue(unsavedAfterAdding.get(), "adding a piece to the bill must count as unsaved work - "
                + "this is exactly what SceneRouter.navigate() checks before leaving the screen (M10 B5)");
        assertFalse(unsavedAfterReset.get(), "resetForNewSale (run after every real save) must clear it");
    }

    @Test
    void navigatingAwayFromAFreshNewSaleScreenDoesNotBlockOnAConfirmationDialog() throws Exception {
        ensureFxToolkitStarted();

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Route> routeAfterNavigatingAway = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                sceneRouter.attachTo(new Stage());
                sceneRouter.navigate(Route.NEW_SALE);
                // No line was ever added, so hasUnsavedWork() is false - if SceneRouter's
                // guard mistakenly fired here, this call would hang forever on a modal
                // Alert.showAndWait() with nothing in the test able to click it (M10 B5).
                sceneRouter.navigate(Route.DASHBOARD);
                routeAfterNavigatingAway.set(sceneRouter.currentRoute());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out - navigate() away from an unmodified New Sale screen should never block");
        }
        if (error.get() != null) {
            throw new AssertionError("Navigation away from a fresh New Sale screen failed", error.get());
        }

        assertEquals(Route.DASHBOARD, routeAfterNavigatingAway.get());
    }

    @Test
    void ctrlOneJumpsToStockAndCtrlSixJumpsToReports() throws Exception {
        ensureFxToolkitStarted();

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Route> routeAfterCtrlOne = new AtomicReference<>();
        AtomicReference<Route> routeAfterCtrlSix = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                sceneRouter.attachTo(new Stage());
                sceneRouter.navigate(Route.DASHBOARD);

                fireAccelerator(sceneRouter.scene(), KeyCode.DIGIT1);
                routeAfterCtrlOne.set(sceneRouter.currentRoute());

                fireAccelerator(sceneRouter.scene(), KeyCode.DIGIT6);
                routeAfterCtrlSix.set(sceneRouter.currentRoute());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out driving keyboard shortcuts on the FX thread");
        }
        if (error.get() != null) {
            throw new AssertionError("Keyboard shortcut navigation failed", error.get());
        }

        // NavSection order is STOCK, SALES, BOOKINGS, PURCHASES, PAYMENTS, REPORTS, SETTINGS
        // (NavSection.java) - Ctrl+1 is Stock (its landing screen is the piece register) and
        // Ctrl+6 is Reports (its landing screen is the stock report) - NFR-14.
        assertEquals(Route.PIECE_REGISTER, routeAfterCtrlOne.get(), "Ctrl+1 should land on Stock's screen");
        assertEquals(Route.STOCK_REPORT, routeAfterCtrlSix.get(), "Ctrl+6 should land on Reports' screen");
    }

    // ---- Reflection helpers into SceneRouter's private shell fields -----------------------

    private boolean isChromeVisible() throws Exception {
        Node navView = navBarView();
        return navView.isVisible() && navView.isManaged();
    }

    private boolean isSectionButtonActive(String label) throws Exception {
        for (Node child : ((javafx.scene.layout.VBox) navBarView()).getChildren()) {
            if (child instanceof javafx.scene.control.Button button && label.equals(button.getText())) {
                return button.getStyleClass().contains("nav-button-active");
            }
        }
        return false;
    }

    private Label topBarTitleLabel() throws Exception {
        for (Node child : ((javafx.scene.layout.HBox) topBarView()).getChildren()) {
            if (child instanceof Label label && label.getStyleClass().contains("top-bar-title")) {
                return label;
            }
        }
        throw new IllegalStateException("No top-bar-title Label found in the top bar");
    }

    private Node navBarView() throws Exception {
        Object navBar = getPrivate(sceneRouter, "navBar");
        return (Node) navBar.getClass().getMethod("view").invoke(navBar);
    }

    private Node topBarView() throws Exception {
        Object topBar = getPrivate(sceneRouter, "topBar");
        return (Node) topBar.getClass().getMethod("view").invoke(topBar);
    }

    private static Object getPrivate(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static <T> T fieldValue(Object target, String fieldName, Class<T> type) throws Exception {
        return type.cast(getPrivate(target, fieldName));
    }

    private static void invokePrivateNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    /** Looks up and runs a Ctrl+{@code keyCode} scene accelerator exactly the way JavaFX's own
     *  input dispatch would - {@link KeyCodeCombination} implements value-based equality over
     *  its code and modifiers, so a freshly built combination here correctly matches whatever
     *  {@code SceneRouter.wireSectionKeyboardShortcuts} registered as the map key. */
    private static void fireAccelerator(Scene scene, KeyCode keyCode) {
        KeyCombination combo = new KeyCodeCombination(keyCode, KeyCombination.CONTROL_DOWN);
        Runnable action = scene.getAccelerators().get(combo);
        if (action == null) {
            throw new IllegalStateException("No accelerator registered for Ctrl+" + keyCode);
        }
        action.run();
    }
}
