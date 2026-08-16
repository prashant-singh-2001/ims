package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesLine;
import com.furnitureims.domain.SalesReturn;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.SalesReturnService;
import com.furnitureims.service.StorageLocationService;
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
 * Exercises milestone M4's billing lifecycle against a real temp-directory SQLite
 * database - the GST/discount arithmetic in {@code SalesInvoiceService.preview}, gap-free
 * invoice numbering (FR-SAL-07), cost_at_sale snapshotting (FR-SAL-09), cancellation
 * (FR-SAL-11) and sales returns (FR-SAL-10) are exactly the logic worth a real assertion.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(M4SalesTest.TestPathsConfig.class)
class M4SalesTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-m4-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private CustomerService customerService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private SalesReturnService salesReturnService;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpShopProfile() {
        shopProfileRepository.save(new ShopProfile("Test Furniture Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");
    }

    private long chairModelId(String code) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        return itemModelService.create(new ItemModel(0, code, "Oak Dining Chair", categoryId, "9403",
                new BigDecimal("18"), null, null, null, null, null, null, null, true, null));
    }

    private Piece newPiece(long modelId, String cost) {
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        return pieceService.createOpeningStock(modelId, 1, Money.ofRupees(cost), locationId, LocalDate.now()).get(0);
    }

    private long customerId(String name, String phone) {
        return customerService.create(
                new Customer(0, name, phone, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void basicIntrastateInvoiceSplitsCgstSgst() {
        long modelId = chairModelId("SALA");
        Piece piece = newPiece(modelId, "2000.00");
        long customerId = customerId("Ravi Kumar", "9000000001");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO,
                LocalDate.now());
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        assertTrue(invoice.invoiceNo().matches("INV/\\d{2}-\\d{2}/\\d{4}"));
        assertEquals(SalesInvoice.Status.ACTIVE, invoice.status());
        assertEquals(Money.ofRupees("5000.00"), invoice.taxableValue());
        assertEquals(Money.ofRupees("450.00"), invoice.cgstAmount());
        assertEquals(Money.ofRupees("450.00"), invoice.sgstAmount());
        assertEquals(Money.ZERO, invoice.igstAmount());
        assertEquals(Money.ofRupees("5900.00"), invoice.grandTotal());

        Piece sold = pieceService.findById(piece.id()).orElseThrow();
        assertEquals(Piece.State.SOLD, sold.state());

        SalesLine line = salesInvoiceService.linesFor(invoiceId).get(0);
        assertEquals(Money.ofRupees("2000.00"), line.costAtSale());
    }

    @Test
    void interstateInvoiceChargesIgstNotCgstSgst() {
        long modelId = chairModelId("SALB");
        Piece piece = newPiece(modelId, "2000.00");
        long customerId = customerId("Delhi Customer", "9000000002");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "07", false, lines, Money.ZERO,
                LocalDate.now());
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        assertTrue(invoice.interstate());
        assertEquals(Money.ofRupees("900.00"), invoice.igstAmount());
        assertEquals(Money.ZERO, invoice.cgstAmount());
        assertEquals(Money.ZERO, invoice.sgstAmount());
    }

    @Test
    void taxInclusivePriceBackCalculatesToSameTaxableValue() {
        long modelId = chairModelId("SALC");
        Piece piece = newPiece(modelId, "2000.00");
        long customerId = customerId("Inclusive Test", "9000000003");

        // Rs.5900 inclusive of 18% GST should back-calculate to exactly Rs.5000 taxable,
        // the same figure the exclusive-pricing test produces from Rs.5000 + 18%.
        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5900.00"), Money.ZERO));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", true, lines, Money.ZERO,
                LocalDate.now());
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        assertEquals(Money.ofRupees("5000.00"), invoice.taxableValue());
        assertEquals(Money.ofRupees("5900.00"), invoice.grandTotal());
    }

    @Test
    void billDiscountIsApportionedProportionallyAcrossLines() {
        long modelId = chairModelId("SALD");
        Piece pieceA = newPiece(modelId, "500.00");
        Piece pieceB = newPiece(modelId, "1500.00");
        long customerId = customerId("Discount Test", "9000000004");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(pieceA.id(), Money.ofRupees("1000.00"), Money.ZERO),
                new SalesInvoiceService.InvoiceLineInput(pieceB.id(), Money.ofRupees("3000.00"), Money.ZERO));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines,
                Money.ofRupees("400.00"), LocalDate.now());
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        // 400 apportioned by 1000:3000 weight -> 100 off line A, 300 off line B.
        // Taxable: 900 + 2700 = 3600; tax at 18% = 648, split 324/324 CGST/SGST.
        assertEquals(Money.ofRupees("3600.00"), invoice.taxableValue());
        assertEquals(Money.ofRupees("324.00"), invoice.cgstAmount());
        assertEquals(Money.ofRupees("324.00"), invoice.sgstAmount());
        assertEquals(Money.ofRupees("4248.00"), invoice.grandTotal());
    }

    @Test
    void sellingAPieceNotInStockIsRejectedAndConsumesNoInvoiceNumber() {
        long modelId = chairModelId("SALE");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Fail Then Succeed", "9000000005");

        // Sell it once, successfully.
        long firstInvoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());
        int firstSeq = sequenceOf(salesInvoiceService.findById(firstInvoiceId).orElseThrow().invoiceNo());

        // Attempting to sell the same (now SOLD) piece again must fail without drawing
        // a number for the next real invoice.
        Piece secondPiece = newPiece(modelId, "1000.00");
        assertThrows(IllegalStateException.class, () -> salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now()));

        long thirdInvoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(secondPiece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());
        SalesInvoice third = salesInvoiceService.findById(thirdInvoiceId).orElseThrow();
        assertEquals(firstSeq + 1, sequenceOf(third.invoiceNo()),
                "the failed attempt in between should not have consumed a number");
    }

    /** Test methods in this class share one Spring context and database (the default for
     *  {@code @SpringBootTest}), so the INVOICE sequence counter accumulates across tests
     *  in whatever order they happen to run - assertions here compare sequence numbers
     *  relative to each other within a test, never against an assumed absolute value. */
    private static int sequenceOf(String invoiceNo) {
        return Integer.parseInt(invoiceNo.substring(invoiceNo.lastIndexOf('/') + 1));
    }

    @Test
    void cancellingAnInvoiceRestoresStockAndNeverReusesTheNumber() {
        long modelId = chairModelId("SALF");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Cancel Test", "9000000006");

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());
        String firstInvoiceNo = salesInvoiceService.findById(invoiceId).orElseThrow().invoiceNo();

        salesInvoiceService.cancelInvoice(invoiceId, "Customer changed their mind");

        SalesInvoice cancelled = salesInvoiceService.findById(invoiceId).orElseThrow();
        assertEquals(SalesInvoice.Status.CANCELLED, cancelled.status());
        assertEquals("Customer changed their mind", cancelled.cancelReason());
        assertEquals(Piece.State.IN_STOCK, pieceService.findById(piece.id()).orElseThrow().state());

        // Cancelling twice is refused.
        assertThrows(IllegalStateException.class, () -> salesInvoiceService.cancelInvoice(invoiceId, "Again"));

        // The next real invoice gets the next number, not the cancelled one reused.
        Piece anotherPiece = newPiece(modelId, "1000.00");
        long secondInvoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(anotherPiece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());
        SalesInvoice second = salesInvoiceService.findById(secondInvoiceId).orElseThrow();
        assertEquals(sequenceOf(firstInvoiceNo) + 1, sequenceOf(second.invoiceNo()));
        assertTrue(!second.invoiceNo().equals(firstInvoiceNo));
    }

    @Test
    void salesReturnCreditsExactLineAmountsAndRestoresStock() {
        long modelId = chairModelId("SALG");
        Piece pieceA = newPiece(modelId, "1000.00");
        Piece pieceB = newPiece(modelId, "1000.00");
        long customerId = customerId("Return Test", "9000000007");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(pieceA.id(), Money.ofRupees("2000.00"), Money.ZERO),
                new SalesInvoiceService.InvoiceLineInput(pieceB.id(), Money.ofRupees("3000.00"), Money.ZERO));
        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO,
                LocalDate.now());

        long returnId = salesReturnService.createReturn(invoiceId, List.of(pieceA.id()), "Wrong colour",
                LocalDate.now(), SalesReturn.RefundMode.CASH_REFUND);
        SalesReturn salesReturn = salesReturnService.returnsFor(invoiceId).stream()
                .filter(r -> r.id() == returnId).findFirst().orElseThrow();

        // Piece A alone: taxable 2000, 18% tax = 360, split 180/180.
        assertEquals(Money.ofRupees("2000.00"), salesReturn.taxableValue());
        assertEquals(Money.ofRupees("180.00"), salesReturn.cgstAmount());
        assertEquals(Money.ofRupees("180.00"), salesReturn.sgstAmount());
        assertEquals(Money.ofRupees("2360.00"), salesReturn.totalAmount());
        assertTrue(salesReturn.creditNoteNo().startsWith("CN/"));

        assertEquals(Piece.State.IN_STOCK, pieceService.findById(pieceA.id()).orElseThrow().state());
        assertEquals(Piece.State.SOLD, pieceService.findById(pieceB.id()).orElseThrow().state());

        assertThrows(IllegalStateException.class, () -> salesReturnService.createReturn(
                invoiceId, List.of(pieceA.id()), "Second attempt", LocalDate.now(),
                SalesReturn.RefundMode.CASH_REFUND));
    }
}
