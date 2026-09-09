package com.piecetrack.ui.purchase;

import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PurchaseBillService;
import com.piecetrack.service.SettingsService;
import com.piecetrack.service.SupplierService;
import com.piecetrack.ui.SceneRouter;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PurchaseBillEntryControllerTest {

    private static volatile boolean fxToolkitStarted = false;

    @BeforeAll
    static synchronized void ensureFxToolkitStarted() throws InterruptedException {
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
    void confirmReceiptFailureKeepsTrackedDraftStateAndDisplaysError() throws Exception {
        PurchaseBillService purchaseBillService = mock(PurchaseBillService.class);
        SupplierService supplierService = mock(SupplierService.class);
        ItemModelService itemModelService = mock(ItemModelService.class);
        SettingsService settingsService = mock(SettingsService.class);
        SceneRouter sceneRouter = mock(SceneRouter.class);

        PurchaseBillEntryController controller = new PurchaseBillEntryController(
                purchaseBillService, supplierService, itemModelService, settingsService, sceneRouter
        );

        Supplier supplier = new Supplier(1L, "Acme Timber", null, null, null, null, null,
                "Maharashtra", "27", null, null, null, Money.ZERO, true, null);
        ItemModel model = new ItemModel(10L, "CHAIR", "Chair", 1L, "9403",
                new BigDecimal("18"), null, true, null);

        // Setup controller controls via reflection
        ComboBox<Supplier> supplierCombo = new ComboBox<>();
        supplierCombo.setValue(supplier);
        setField(controller, "supplierCombo", supplierCombo);

        TextField billNoField = new TextField("BILL-1001");
        setField(controller, "billNoField", billNoField);

        DatePicker billDatePicker = new DatePicker(LocalDate.now());
        setField(controller, "billDatePicker", billDatePicker);

        DatePicker receivedDatePicker = new DatePicker(LocalDate.now());
        setField(controller, "receivedDatePicker", receivedDatePicker);

        TextField freightField = new TextField("");
        setField(controller, "freightField", freightField);

        TextField loadingChargesField = new TextField("");
        setField(controller, "loadingChargesField", loadingChargesField);

        TextField otherChargesField = new TextField("");
        setField(controller, "otherChargesField", otherChargesField);

        TextArea notesArea = new TextArea("");
        setField(controller, "notesArea", notesArea);

        Label errorLabel = new Label("");
        setField(controller, "errorLabel", errorLabel);

        // Set up rows list
        List<?> rows = (List<?>) getField(controller, "rows");
        // LineRowControls record constructor: modelCombo, quantitySpinner, rateField, discountField, gstRateField, container
        Class<?> lineRowControlsClass = Class.forName("com.piecetrack.ui.purchase.PurchaseBillEntryController$LineRowControls");
        Constructor<?> ctor = lineRowControlsClass.getDeclaredConstructor(
                ComboBox.class, Spinner.class, TextField.class, TextField.class, TextField.class, HBox.class
        );
        ctor.setAccessible(true);

        ComboBox<ItemModel> modelCombo = new ComboBox<>();
        modelCombo.setValue(model);
        Spinner<Integer> quantitySpinner = new Spinner<>(1, 100, 2);
        TextField rateField = new TextField("500");
        TextField discountField = new TextField("0");
        TextField gstRateField = new TextField("18");
        HBox container = new HBox();

        Object rowControls = ctor.newInstance(modelCombo, quantitySpinner, rateField, discountField, gstRateField, container);
        @SuppressWarnings("unchecked")
        List<Object> rowsList = (List<Object>) rows;
        rowsList.add(rowControls);

        long createdBillId = 42L;
        when(purchaseBillService.saveDraft(
                eq(null), eq(supplier.id()), eq("BILL-1001"), any(), any(),
                any(), any(), any(), any(), any()
        )).thenReturn(createdBillId);

        // Simulate confirmReceipt failing with a database lock contention RuntimeException
        doThrow(new RuntimeException("SQLite lock contention: database is locked"))
                .when(purchaseBillService).confirmReceipt(createdBillId);

        // Auto-confirm dialogs that appear during showAndWait
        ScheduledExecutorService autoDialogClicker = Executors.newSingleThreadScheduledExecutor();
        autoDialogClicker.scheduleAtFixedRate(() -> {
            Platform.runLater(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof Stage stage && stage.getScene() != null
                            && stage.getScene().getRoot() instanceof DialogPane pane) {
                        Button okButton = (Button) pane.lookupButton(ButtonType.OK);
                        if (okButton != null) {
                            okButton.fire();
                        }
                    }
                }
            });
        }, 30, 30, TimeUnit.MILLISECONDS);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                Method onConfirmMethod = PurchaseBillEntryController.class.getDeclaredMethod("onConfirmReceiptClicked");
                onConfirmMethod.setAccessible(true);
                onConfirmMethod.invoke(controller);
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            autoDialogClicker.shutdownNow();
            fail("Timed out waiting for onConfirmReceiptClicked");
        }
        autoDialogClicker.shutdownNow();

        if (err.get() != null) {
            fail("Exception thrown while invoking onConfirmReceiptClicked", err.get());
        }

        // Verify state is tracked!
        Long editingId = (Long) getField(controller, "editingId");
        Boolean pendingIsNew = (Boolean) getField(controller, "pendingIsNew");

        assertEquals(42L, editingId, "editingId must be set to the saved draft billId even if confirmReceipt fails");
        assertFalse(pendingIsNew, "pendingIsNew must be false after draft is saved");
        assertEquals("Edit Purchase Bill (Draft) - BILL-1001", controller.screenTitleProperty().get());
        assertEquals("SQLite lock contention: database is locked", errorLabel.getText());

        // Now verify retry: save() uses editingId=42L instead of null!
        Method saveMethod = PurchaseBillEntryController.class.getDeclaredMethod("save");
        saveMethod.setAccessible(true);
        saveMethod.invoke(controller);

        Mockito.verify(purchaseBillService).saveDraft(
                eq(42L), eq(supplier.id()), eq("BILL-1001"), any(), any(),
                any(), any(), any(), any(), any()
        );
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
