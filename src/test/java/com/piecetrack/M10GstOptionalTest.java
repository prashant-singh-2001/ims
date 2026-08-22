package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.domain.SalesLine;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.CustomerService;
import com.piecetrack.service.DocumentService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.PurchaseBillService;
import com.piecetrack.service.SalesInvoiceService;
import com.piecetrack.service.SettingsService;
import com.piecetrack.service.StorageLocationService;
import com.piecetrack.service.SupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 Workstream C: with {@link SettingsService#isGstEnabled()} off, GST-specific fields
 * that are {@code NOT NULL} with no default (HSN code, GST rate, place of supply, supplier
 * state) must never block a save, and every computed tax figure must be genuinely zero -
 * not just hidden in the UI. With it on (the default), every existing computation must stay
 * byte-identical to pre-M10 behaviour, which the rest of the suite (M3PurchasesTest,
 * M4SalesTest) already covers exhaustively - this class only adds a couple of on/off
 * regression guards rather than re-proving GST-on arithmetic a second time.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(M10GstOptionalTest.TestPathsConfig.class)
class M10GstOptionalTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-m10-gst-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private CustomerService customerService;
    @Autowired private SupplierService supplierService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private PurchaseBillService purchaseBillService;
    @Autowired private DocumentService documentService;
    @Autowired private SettingsService settingsService;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpShopProfile() {
        shopProfileRepository.save(new ShopProfile("Test Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");
        // Every test method sets the toggle to exactly what it needs itself (below) rather
        // than relying on this default - test methods in this class share one Spring context
        // and database, so an assumed "starts ON" would be order-dependent.
        settingsService.setGstEnabled(true);
    }

    private long chairModelId(String code, String hsnCode, BigDecimal gstRate) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Other")).findFirst().orElseThrow().id();
        return itemModelService.create(new ItemModel(0, code, "Test Widget", categoryId, hsnCode, gstRate,
                null, true, null));
    }

    private Piece newPiece(long modelId, String cost) {
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst()
                .map(l -> l.id())
                .orElseGet(() -> storageLocationService.create("Godown"));
        return pieceService.createOpeningStock(modelId, 1, Money.ofRupees(cost), locationId, LocalDate.now()).get(0);
    }

    private long customerId(String name, String phone) {
        return customerService.create(
                new com.piecetrack.domain.Customer(0, name, phone, null, null, null, null, null, null, null,
                        null, null));
    }

    // ---- ItemModelService --------------------------------------------------------------

    @Test
    void itemModelSavesWithoutHsnOrGstRateWhenGstIsOff() {
        settingsService.setGstEnabled(false);

        long modelId = chairModelId("GSTA", null, null);

        ItemModel saved = itemModelService.findById(modelId).orElseThrow();
        assertEquals("", saved.hsnCode(), "hsn_code is NOT NULL with no default - blank is the M10 sentinel");
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.gstRate()));
    }

    @Test
    void itemModelStillRequiresHsnAndGstRateWhenGstIsOn() {
        settingsService.setGstEnabled(true);

        assertThrows(IllegalArgumentException.class, () -> chairModelId("GSTB", null, null),
                "GST on must keep behaving exactly as it did before M10 - no regression from making this conditional");
    }

    // ---- SupplierService -----------------------------------------------------------------

    @Test
    void supplierSavesWithoutStateWhenGstIsOff() {
        settingsService.setGstEnabled(false);

        long supplierId = supplierService.create(new Supplier(0, "No-State Supplier", null, null, null, null,
                null, null, null, null, null, null, Money.ZERO, true, null));

        Supplier saved = supplierService.findById(supplierId).orElseThrow();
        assertEquals("27", saved.stateCode(), "supplier.state_code is NOT NULL - sentinel is the shop's own state");
        assertEquals("Maharashtra", saved.stateName());
    }

    @Test
    void supplierStillRequiresStateWhenGstIsOn() {
        settingsService.setGstEnabled(true);

        assertThrows(IllegalArgumentException.class, () -> supplierService.create(new Supplier(0, "No State", null,
                null, null, null, null, null, null, null, null, null, Money.ZERO, true, null)));
    }

    // ---- SalesInvoiceService --------------------------------------------------------------

    @Test
    void salesPreviewIsZeroTaxWithMatchingGrandTotalWhenGstIsOff() {
        // The model is created while GST is ON, so it carries a real, non-zero stored rate -
        // proving the toggle masks a historical rate, not merely that a fresh model defaults
        // to zero.
        settingsService.setGstEnabled(true);
        long modelId = chairModelId("GSTC", "9403", new BigDecimal("18"));
        Piece piece = newPiece(modelId, "500.00");

        settingsService.setGstEnabled(false);
        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("999.00"), Money.ZERO));

        // null place of supply - the billing screen never collects it once GST is off.
        SalesInvoiceService.InvoicePreview preview = salesInvoiceService.preview(null, lines, false, Money.ZERO);

        assertFalse(preview.interstate());
        assertEquals(Money.ZERO, preview.cgstAmount());
        assertEquals(Money.ZERO, preview.sgstAmount());
        assertEquals(Money.ZERO, preview.igstAmount());
        assertEquals(Money.ofRupees("999.00"), preview.taxableValue());
        assertEquals(preview.taxableValue(), preview.grandTotal(),
                "with zero tax and a whole-rupee price, grand total must equal taxable value exactly");
    }

    @Test
    void createInvoiceWritesShopStateCodeAndZeroTaxLinesWhenGstIsOff() {
        settingsService.setGstEnabled(true);
        long modelId = chairModelId("GSTD", "9403", new BigDecimal("18"));
        Piece piece = newPiece(modelId, "500.00");
        long customerId = customerId("GST Off Customer", "9000000010");

        settingsService.setGstEnabled(false);
        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("999.00"), Money.ZERO));

        long invoiceId = salesInvoiceService.createInvoice(customerId, null, false, lines, Money.ZERO,
                LocalDate.now());
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        assertEquals("27", invoice.placeOfSupplyStateCode(),
                "place_of_supply_state_code is NOT NULL - sentinel is the shop's own state");
        assertFalse(invoice.interstate());
        assertEquals(Money.ZERO, invoice.cgstAmount());
        assertEquals(Money.ZERO, invoice.sgstAmount());
        assertEquals(Money.ZERO, invoice.igstAmount());
        assertEquals(invoice.taxableValue(), invoice.grandTotal());

        SalesLine line = salesInvoiceService.linesFor(invoiceId).get(0);
        assertEquals("", line.hsnSnapshot(),
                "a GST-off sale must not leak the model's real historical HSN onto the line");
        assertEquals(0, BigDecimal.ZERO.compareTo(line.gstRate()));
    }

    @Test
    void salesPreviewStillComputesRealTaxWhenGstIsOn() {
        settingsService.setGstEnabled(true);
        long modelId = chairModelId("GSTE", "9403", new BigDecimal("18"));
        Piece piece = newPiece(modelId, "2000.00");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO));
        SalesInvoiceService.InvoicePreview preview = salesInvoiceService.preview("27", lines, false, Money.ZERO);

        // Byte-identical to M4SalesTest.basicIntrastateInvoiceSplitsCgstSgst - proves the
        // conditional branch changes nothing when GST is on.
        assertEquals(Money.ofRupees("450.00"), preview.cgstAmount());
        assertEquals(Money.ofRupees("450.00"), preview.sgstAmount());
        assertEquals(Money.ofRupees("5900.00"), preview.grandTotal());
    }

    // ---- DocumentService (M10 D4/D5) -------------------------------------------------------

    @Test
    void invoicePdfHasNoGstContentWhenGstIsOff() throws IOException {
        // Model created while GST is on, so it carries a real HSN/rate on record - the PDF
        // must still print no trace of GST once the sale itself is made with GST off,
        // proving the toggle (not merely a freshly-sentinelled model) is what drives this.
        settingsService.setGstEnabled(true);
        long modelId = chairModelId("GSTH", "9403", new BigDecimal("18"));
        Piece piece = newPiece(modelId, "500.00");
        long customerId = customerId("PDF GST Off Customer", "9000000011");

        settingsService.setGstEnabled(false);
        long invoiceId = salesInvoiceService.createInvoice(customerId, null, false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("999.00"), Money.ZERO)),
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

        assertFalse(content.contains("GSTIN"), "no GSTIN label anywhere once GST is off");
        assertFalse(content.contains("HSN"), "no HSN column/label anywhere once GST is off");
        assertFalse(content.contains("CGST"), "no CGST anywhere once GST is off");
        assertFalse(content.contains("Place of Supply"), "no place of supply anywhere once GST is off");
        assertTrue(content.contains("INVOICE"), "still a plain INVOICE, just not TAX INVOICE / BILL OF SUPPLY");
        assertFalse(content.contains("TAX INVOICE"), "must not claim to be a tax invoice with no tax on it");

        assertTrue(content.contains(invoice.invoiceNo()), "invoice number must still appear");
        assertTrue(content.contains("Test Widget"), "the item itself must still appear");
        // Money.toDisplayString() prefixes an actual Rupee sign (U+20B9), which the PDF's
        // base Helvetica font has no glyph for and does not survive text extraction - the
        // pre-existing a9_ test avoids it for the same reason, so this checks the formatted
        // number only (no tax on a Rs.999 line means the grand total is exactly 999.00).
        assertTrue(content.contains("999.00"), "the grand total must still appear");
        assertTrue(content.toLowerCase().contains("rupees"), "amount in words must still appear (FR-DOC-01)");
    }

    // ---- PurchaseBillService --------------------------------------------------------------

    @Test
    void purchasePreviewIsZeroTaxWhenGstIsOff() {
        settingsService.setGstEnabled(true);
        long modelId = chairModelId("GSTF", "9403", new BigDecimal("18"));
        long supplierId = supplierService.create(new Supplier(0, "Purchase Test Supplier", null, null, null, null,
                null, "Delhi", "07", null, null, null, Money.ZERO, true, null));

        settingsService.setGstEnabled(false);
        // A non-zero rate passed straight in, exactly as a stale/unhidden field might still
        // send one - preview must force it to zero anyway, not merely default it.
        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 2, Money.ofRupees("500.00"), Money.ZERO,
                        new BigDecimal("18")));

        PurchaseBillService.PreviewTotals totals = purchaseBillService.preview(supplierId, lines, Money.ZERO,
                Money.ZERO, Money.ZERO);

        assertFalse(totals.interstate(), "interstate must be forced off regardless of the supplier's real state");
        assertEquals(Money.ZERO, totals.cgstAmount());
        assertEquals(Money.ZERO, totals.sgstAmount());
        assertEquals(Money.ZERO, totals.igstAmount());
        assertEquals(Money.ofRupees("1000.00"), totals.taxableValue());
        assertEquals(totals.taxableValue(), totals.grandTotal());
    }

    @Test
    void purchaseSaveDraftSucceedsWithoutGstRateWhenGstIsOff() {
        settingsService.setGstEnabled(true);
        long modelId = chairModelId("GSTG", "9403", new BigDecimal("18"));
        long supplierId = supplierService.create(new Supplier(0, "Draft Test Supplier", null, null, null, null,
                null, "Maharashtra", "27", null, null, null, Money.ZERO, true, null));

        settingsService.setGstEnabled(false);
        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 1, Money.ofRupees("500.00"), Money.ZERO, BigDecimal.ZERO));

        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-GST-OFF-1", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);

        PurchaseBill bill = purchaseBillService.findById(billId).orElseThrow();
        assertFalse(bill.interstate());
        assertEquals(Money.ZERO, bill.cgstAmount());
        assertEquals(Money.ZERO, bill.sgstAmount());
        assertEquals(Money.ZERO, bill.igstAmount());
    }
}
