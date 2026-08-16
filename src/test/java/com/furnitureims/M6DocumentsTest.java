package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.PurchaseReturn;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesReturn;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.DocumentService;
import com.furnitureims.service.EmailService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.PurchaseBillService;
import com.furnitureims.service.PurchaseReturnService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.SalesReturnService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.service.SupplierService;
import com.furnitureims.util.AmountInWords;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises milestone M6's document generation against a real temp-directory SQLite
 * database and the real OpenPDF writer - a PDF actually landing on disk at the path the DB
 * row now points to (FR-DOC-01/02/05) is exactly the kind of thing a mock would paper over.
 * Content is verified by file existence and non-trivial size, not by parsing the PDF back
 * apart - {@link AmountInWords} is covered separately with exact-string assertions since
 * that arithmetic is easy to get subtly wrong.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(M6DocumentsTest.TestPathsConfig.class)
class M6DocumentsTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-m6-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private CustomerService customerService;
    @Autowired private SupplierService supplierService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private SalesReturnService salesReturnService;
    @Autowired private PurchaseBillService purchaseBillService;
    @Autowired private PurchaseReturnService purchaseReturnService;
    @Autowired private DocumentService documentService;
    @Autowired private EmailService emailService;
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
                new BigDecimal("18"), null, null, null, null, null, null, null, true, null));
    }

    private Piece newPiece(long modelId, String cost) {
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        return pieceService.createOpeningStock(modelId, 1, Money.ofRupees(cost), locationId, LocalDate.now()).get(0);
    }

    @Test
    void invoicePdfIsWrittenToDiskAndPathIsRecorded() {
        long modelId = chairModelId("DOCA");
        Piece pieceA = newPiece(modelId, "2000.00");
        Piece pieceB = newPiece(modelId, "2000.00");
        long customerId = customerService.create(new Customer(0, "Ravi Kumar", "9000000001",
                "ravi@example.com", null, null, null, null, null, null, null, null));

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(pieceA.id(), Money.ofRupees("5000.00"), Money.ZERO),
                new SalesInvoiceService.InvoiceLineInput(pieceB.id(), Money.ofRupees("5000.00"), Money.ZERO));
        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO,
                LocalDate.now());

        Path pdf = documentService.generateInvoicePdf(invoiceId);
        assertTrue(Files.exists(pdf), "invoice PDF should exist on disk");
        assertTrue(sizeOf(pdf) > 1000, "invoice PDF should not be a near-empty stub file");
        assertTrue(pdf.toString().contains("Invoices"));

        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();
        assertEquals(pdf, documentService.resolve(invoice.pdfPath()),
                "the DB-stored relative path should resolve back to the file just written");

        // Regeneration (FR-DOC-02) overwrites the same file rather than accumulating copies.
        Path regenerated = documentService.generateInvoicePdf(invoiceId);
        assertEquals(pdf, regenerated);
        assertTrue(Files.exists(regenerated));
    }

    @Test
    void creditNotePdfIsWrittenAndLinkedToTheReturn() {
        long modelId = chairModelId("DOCB");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerService.create(new Customer(0, "Return Customer", "9000000002",
                null, null, null, null, null, null, null, null, null));

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());

        long returnId = salesReturnService.createReturn(invoiceId, List.of(piece.id()), "Wrong colour",
                LocalDate.now(), SalesReturn.RefundMode.CASH_REFUND);

        Path pdf = documentService.generateCreditNotePdf(returnId);
        assertTrue(Files.exists(pdf));
        assertTrue(sizeOf(pdf) > 1000);

        SalesReturn salesReturn = salesReturnService.returnsFor(invoiceId).stream()
                .filter(r -> r.id() == returnId).findFirst().orElseThrow();
        assertEquals(pdf, documentService.resolve(salesReturn.pdfPath()));
    }

    @Test
    void debitNotePdfIsWrittenAndLinkedToTheReturn() {
        long modelId = chairModelId("DOCC");
        long supplierId = supplierService.create(new Supplier(0, "Debit Note Supplier", null, null, null,
                null, null, "Maharashtra", "27", null, null, null, Money.ZERO, true, null));

        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 2, Money.ofRupees("1000.00"), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "DOC-BILL-1", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);
        purchaseBillService.confirmReceipt(billId);

        var line = purchaseBillService.linesFor(billId).get(0);
        var created = pieceService.findByPurchaseLineId(line.id());

        long returnId = purchaseReturnService.createReturn(billId, List.of(created.get(0).id()),
                "Manufacturing defect", LocalDate.now());

        Path pdf = documentService.generateDebitNotePdf(returnId);
        assertTrue(Files.exists(pdf));
        assertTrue(sizeOf(pdf) > 1000);

        PurchaseReturn purchaseReturn = purchaseReturnService.returnsFor(billId).stream()
                .filter(r -> r.id() == returnId).findFirst().orElseThrow();
        assertEquals(pdf, documentService.resolve(purchaseReturn.pdfPath()));
    }

    @Test
    void unconfiguredSmtpFailsClearlyRatherThanSilently() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> emailService.sendTestEmail("someone@example.com"));
        assertTrue(ex.getMessage().toLowerCase().contains("smtp"));
    }

    @Test
    void amountInWordsUsesIndianLakhCroreNumbering() {
        assertEquals("Rupees Zero Only", AmountInWords.toWords(Money.ZERO));
        assertEquals("Rupees One Hundred Only", AmountInWords.toWords(Money.ofRupees("100.00")));
        assertEquals("Rupees One Hundred and Fifty Paise Only", AmountInWords.toWords(Money.ofRupees("100.50")));
        assertEquals("Rupees Two Hundred and Thirty-Four Only", AmountInWords.toWords(Money.ofRupees("234.00")));
        assertEquals("Rupees One Lakh Fifty Thousand Only", AmountInWords.toWords(Money.ofRupees("150000.00")));
        assertEquals("Rupees One Lakh Fifty Thousand Thirty-Four Only",
                AmountInWords.toWords(Money.ofRupees("150034.00")));
        assertEquals("Rupees One Crore Twenty-Three Lakh Forty-Five Thousand Six Hundred and Seventy-Eight Only",
                AmountInWords.toWords(Money.ofRupees("12345678.00")));
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new AssertionError("Could not read size of " + path, e);
        }
    }
}
