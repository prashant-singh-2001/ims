package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Piece;
import com.furnitureims.money.Money;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.ui.catalogue.PieceDetailController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

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
        fxToolkitStarted = true;
    }

    @Autowired private ApplicationContext applicationContext;
    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private PieceDetailController pieceDetailController;

    /** Screens with no prerequisite state - loadable straight from a fresh database. */
    @Test
    void standaloneScreensLoadWithoutError() throws Exception {
        ensureFxToolkitStarted();

        List<String> screens = List.of(
                "/fxml/shell/dashboard-placeholder.fxml",
                "/fxml/catalogue/categories-locations.fxml",
                "/fxml/catalogue/item-model-list.fxml",
                "/fxml/catalogue/item-model-editor.fxml",
                "/fxml/catalogue/piece-register.fxml",
                "/fxml/catalogue/opening-stock-entry.fxml"
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

    private void loadOnFxThread(String classpathFxml) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Parent> result = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                URL location = getClass().getResource(classpathFxml);
                FXMLLoader loader = new FXMLLoader(location);
                loader.setControllerFactory(applicationContext::getBean);
                result.set(loader.load());
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
