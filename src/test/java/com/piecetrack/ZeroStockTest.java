package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.Customer;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ItemModelSearchCriteria;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.CustomerService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.SalesInvoiceService;
import com.piecetrack.service.StorageLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Zero Stock action against a real temp-directory SQLite database - owner
 * request: "add stock deletion", refined in discussion to "the count reads 0" rather than a
 * blanket hard delete. {@link #aPieceOnACancelledInvoiceIsWrittenOffNotDeleted} is the test
 * that matters most here: a piece a cancelled invoice's {@code sales_line} still points at
 * (FR-SAL-11 keeps the invoice forever, never deleted) must be written off, not deleted -
 * foreign keys are enforced ({@code DataSourceConfig.enforceForeignKeys(true)}), and even
 * without that enforcement, deleting it would leave a retained document pointing at nothing.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(ZeroStockTest.TestPathsConfig.class)
class ZeroStockTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-zerostock-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private CustomerService customerService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpShopProfile() {
        shopProfileRepository.save(new ShopProfile("Test Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");
    }

    private long createModel(String code) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Other")).findFirst().orElseThrow().id();
        return itemModelService.create(new ItemModel(0, code, "Test Widget", categoryId, "9403",
                new BigDecimal("18"), null, true, null));
    }

    private long godownLocationId() {
        return storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst()
                .map(l -> l.id())
                .orElseGet(() -> storageLocationService.create("Godown"));
    }

    private long customerId() {
        return customerService.create(new Customer(0, "Test Customer", "9000000099", null, null, null, null,
                null, null, null, null, null));
    }

    private int inStockCount(long modelId) {
        return (int) itemModelService.search(ItemModelSearchCriteria.defaultCriteria()).stream()
                .filter(s -> s.model().id() == modelId).findFirst().orElseThrow().inStockCount();
    }

    @Test
    void zeroingDeletesCleanInStockPieces() {
        long modelId = createModel("ZSA");
        List<Piece> pieces = pieceService.createOpeningStock(
                modelId, 3, Money.ofRupees("1000.00"), godownLocationId(), LocalDate.now());

        PieceService.ZeroStockOutcome outcome =
                pieceService.zeroStockForItemModel(modelId, "Physical count correction");

        assertEquals(3, outcome.deletedCount());
        assertEquals(0, outcome.writtenOffCount());
        assertEquals(0, inStockCount(modelId));
        for (Piece piece : pieces) {
            assertTrue(pieceService.findById(piece.id()).isEmpty(), "a cleanly-deleted piece must be gone entirely");
        }
    }

    @Test
    void aPieceOnACancelledInvoiceIsWrittenOffNotDeleted() {
        long modelId = createModel("ZSB");
        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("2000.00"), godownLocationId(), LocalDate.now()).get(0);
        long customerId = customerId();

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO));
        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO,
                LocalDate.now());
        salesInvoiceService.cancelInvoice(invoiceId, "Customer changed their mind");

        // FR-SAL-11: cancelling restores the piece to IN_STOCK while sales_line still points
        // at it - this is the exact state that makes zeroing dangerous if done naively.
        assertEquals(Piece.State.IN_STOCK, pieceService.findById(piece.id()).orElseThrow().state());

        PieceService.ZeroStockOutcome outcome = pieceService.zeroStockForItemModel(modelId, "Zeroing for test");

        assertEquals(0, outcome.deletedCount());
        assertEquals(1, outcome.writtenOffCount());
        assertEquals(0, inStockCount(modelId), "the In Stock count must read 0 either way");

        Piece afterZeroing = pieceService.findById(piece.id()).orElseThrow();
        assertEquals(Piece.State.WRITTEN_OFF, afterZeroing.state(),
                "must survive as written off, not be deleted out from under the cancelled invoice");

        // The cancelled invoice and its line must still resolve cleanly - proof the piece
        // was not deleted from under a document FR-SAL-11 requires to be retained forever.
        SalesInvoice cancelled = salesInvoiceService.findById(invoiceId).orElseThrow();
        assertEquals(SalesInvoice.Status.CANCELLED, cancelled.status());
        assertEquals(1, salesInvoiceService.linesFor(invoiceId).size());
        assertEquals(piece.id(), salesInvoiceService.linesFor(invoiceId).get(0).pieceId());
    }

    @Test
    void aSoldPieceIsUntouchedByZeroingItsModel() {
        long modelId = createModel("ZSC");
        Piece sold = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("2000.00"), godownLocationId(), LocalDate.now()).get(0);
        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(sold.id(), Money.ofRupees("5000.00"), Money.ZERO));
        salesInvoiceService.createInvoice(customerId(), "27", false, lines, Money.ZERO, LocalDate.now());
        assertEquals(Piece.State.SOLD, pieceService.findById(sold.id()).orElseThrow().state());

        PieceService.ZeroStockOutcome outcome = pieceService.zeroStockForItemModel(modelId, "Nothing to zero");

        assertEquals(0, outcome.deletedCount());
        assertEquals(0, outcome.writtenOffCount());
        assertEquals(Piece.State.SOLD, pieceService.findById(sold.id()).orElseThrow().state(),
                "a SOLD piece is not IN_STOCK, so zeroing must never touch it");
    }

    @Test
    void zeroingRequiresAReason() {
        long modelId = createModel("ZSD");
        pieceService.createOpeningStock(modelId, 1, Money.ofRupees("500.00"), godownLocationId(), LocalDate.now());

        assertThrows(IllegalArgumentException.class, () -> pieceService.zeroStockForItemModel(modelId, "  "));
    }

    /** {@code OpeningStockEntryController} relies on this guard staying in place: quantity 0
     *  on the new-item row is handled entirely in the controller (create the model, skip
     *  calling this method), never by loosening this check - see its own class Javadoc. */
    @Test
    void openingStockStillRejectsAZeroQuantity() {
        long modelId = createModel("ZSE");
        assertThrows(IllegalArgumentException.class, () -> pieceService.createOpeningStock(
                modelId, 0, Money.ofRupees("500.00"), godownLocationId(), LocalDate.now()));
    }
}
