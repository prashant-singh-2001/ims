package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.domain.PurchaseLine;
import com.piecetrack.domain.PurchaseReturn;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ItemModelRepository;
import com.piecetrack.repository.PieceRepository;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.PurchaseBillService;
import com.piecetrack.service.PurchaseReturnService;
import com.piecetrack.service.SupplierService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises milestone M3's purchase lifecycle against a real temp-directory SQLite
 * database - landed-cost apportionment (FR-PUR-06), the CGST/SGST vs IGST split
 * (FR-PUR-04), receipt reversal blocking (FR-PUR-08) and purchase returns (FR-PUR-07) are
 * exactly the arithmetic and state-machine logic worth a real assertion, not a mock.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(M3PurchasesTest.TestPathsConfig.class)
class M3PurchasesTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-m3-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private SupplierService supplierService;
    @Autowired private PurchaseBillService purchaseBillService;
    @Autowired private PurchaseReturnService purchaseReturnService;
    @Autowired private PieceService pieceService;
    @Autowired private ShopProfileRepository shopProfileRepository;
    @Autowired private ItemModelRepository itemModelRepository;
    @Autowired private PieceRepository pieceRepository;
    @Autowired private JdbcTemplate jdbc;

    /** Both the shop profile and an owner account must exist for other setup-gated
     *  behaviour to make sense, and the shop's state code is what the interstate/
     *  intrastate GST split (FR-PUR-04) compares suppliers against. */
    @BeforeEach
    void setUpShopProfile() {
        shopProfileRepository.save(new ShopProfile("Test Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES ('owner', 'x', 'OWNER')");
    }

    private long chairModelId(String code) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Other")).findFirst().orElseThrow().id();
        return itemModelService.create(new ItemModel(0, code, "Test Widget", categoryId, "9403",
                new BigDecimal("18"), null, true, null));
    }

    private long intrastateSupplier() {
        return supplierService.create(new Supplier(0, "Local Timber Co", null, null, null, null, null,
                "Maharashtra", "27", null, null, null, Money.ZERO, true, null));
    }

    private long interstateSupplier() {
        return supplierService.create(new Supplier(0, "Delhi Furnishings", null, null, null, null, null,
                "Delhi", "07", null, null, null, Money.ZERO, true, null));
    }

    @Test
    void confirmingReceiptApportionsLandedCostExactlyAcrossPieces() {
        long modelId = chairModelId("APCA");
        long supplierId = intrastateSupplier();

        // 1 sofa-equivalent line of 6 chairs at Rs.3000 each, plus Rs.600 freight - freight
        // should spread across all 6 pieces so their costs sum to taxable value + freight,
        // to the paisa, exactly as FR-PUR-06 requires.
        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 6, Money.ofRupees("3000.00"), Money.ZERO,
                        new BigDecimal("18")));

        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-001", LocalDate.now(),
                LocalDate.now(), Money.ofRupees("600.00"), Money.ZERO, Money.ZERO, null, lines);

        PurchaseBill draft = purchaseBillService.findById(billId).orElseThrow();
        assertEquals(PurchaseBill.Status.DRAFT, draft.status());
        assertEquals(Money.ofRupees("18000.00"), draft.taxableValue());
        // Intrastate: CGST 9% + SGST 9% of 18000 = 1620 + 1620.
        assertEquals(Money.ofRupees("1620.00"), draft.cgstAmount());
        assertEquals(Money.ofRupees("1620.00"), draft.sgstAmount());
        assertEquals(Money.ZERO, draft.igstAmount());

        purchaseBillService.confirmReceipt(billId);
        PurchaseBill received = purchaseBillService.findById(billId).orElseThrow();
        assertEquals(PurchaseBill.Status.RECEIVED, received.status());

        List<PurchaseLine> savedLines = purchaseBillService.linesFor(billId);
        assertEquals(1, savedLines.size());
        List<Piece> created = pieceService.findByPurchaseLineId(savedLines.get(0).id());

        assertEquals(6, created.size());
        assertThat(created).allMatch(p -> p.sourceType() == Piece.SourceType.PURCHASE);
        assertThat(created).allMatch(p -> p.state() == Piece.State.IN_STOCK);
        assertThat(created).allMatch(p -> p.locationId() == null);

        long totalLandedCostPaisa = created.stream().mapToLong(p -> p.landedCost().paisa()).sum();
        long expectedPaisa = draft.taxableValue().paisa() + Money.ofRupees("600.00").paisa();
        assertEquals(expectedPaisa, totalLandedCostPaisa);

        // Rs.18000 taxable + Rs.600 freight = Rs.18600 over 6 pieces = exactly Rs.3100 each,
        // no remainder to worry about in this particular case.
        assertThat(created).allMatch(p -> p.landedCost().equals(Money.ofRupees("3100.00")));
    }

    @Test
    void interstatePurchaseChargesIgstNotCgstSgst() {
        long modelId = chairModelId("APCB");
        long supplierId = interstateSupplier();

        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 2, Money.ofRupees("1000.00"), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-IGST-1", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);

        PurchaseBill bill = purchaseBillService.findById(billId).orElseThrow();
        assertTrue(bill.interstate());
        assertEquals(Money.ofRupees("360.00"), bill.igstAmount());
        assertEquals(Money.ZERO, bill.cgstAmount());
        assertEquals(Money.ZERO, bill.sgstAmount());
        assertEquals(Money.ofRupees("2360.00"), bill.grandTotal());
    }

    @Test
    void receiptReversalIsBlockedOnceAPieceLeavesStockAndWorksOtherwise() {
        long modelId = chairModelId("APCC");
        long supplierId = intrastateSupplier();
        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 2, Money.ofRupees("1000.00"), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-002", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);
        purchaseBillService.confirmReceipt(billId);

        PurchaseLine line = purchaseBillService.linesFor(billId).get(0);
        List<Piece> created = pieceService.findByPurchaseLineId(line.id());
        assertEquals(2, created.size());

        // Move one piece out of IN_STOCK by hand (sales module is M4) to simulate it having
        // been sold, then confirm reversal is refused and names the blocking tag.
        pieceRepository.updateState(created.get(0).id(), Piece.State.SOLD, null);

        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> purchaseBillService.reverseReceipt(billId));
        assertTrue(blocked.getMessage().contains(created.get(0).tag()));

        // Put it back and confirm reversal now succeeds and removes both pieces.
        pieceRepository.updateState(created.get(0).id(), Piece.State.IN_STOCK, null);
        purchaseBillService.reverseReceipt(billId);

        assertEquals(PurchaseBill.Status.DRAFT, purchaseBillService.findById(billId).orElseThrow().status());
        assertThat(pieceService.findByPurchaseLineId(line.id())).isEmpty();
    }

    @Test
    void purchaseReturnCreditsBackExactShareAndMovesPieces() {
        long modelId = chairModelId("APCD");
        long supplierId = intrastateSupplier();
        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 4, Money.ofRupees("1000.00"), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-003", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);
        purchaseBillService.confirmReceipt(billId);

        PurchaseLine line = purchaseBillService.linesFor(billId).get(0);
        List<Piece> created = pieceService.findByPurchaseLineId(line.id());
        assertEquals(4, created.size());

        // Return 1 of the 4 pieces - the line's taxable value is 4 x Rs.1000 = Rs.4000, so
        // one piece's share is a quarter of that: Rs.1000 taxable, Rs.90 CGST + Rs.90 SGST
        // (line tax is 18% of 4000 = 720, halved to 360 CGST/SGST, quartered per piece).
        long returnId = purchaseReturnService.createReturn(billId, List.of(created.get(0).id()),
                "Manufacturing defect", LocalDate.now());
        PurchaseReturn purchaseReturn = purchaseReturnService.returnsFor(billId).stream()
                .filter(r -> r.id() == returnId).findFirst().orElseThrow();

        assertEquals(Money.ofRupees("1000.00"), purchaseReturn.taxableValue());
        assertEquals(Money.ofRupees("90.00"), purchaseReturn.cgstAmount());
        assertEquals(Money.ofRupees("90.00"), purchaseReturn.sgstAmount());
        assertEquals(Money.ofRupees("1180.00"), purchaseReturn.totalAmount());
        assertTrue(purchaseReturn.debitNoteNo().startsWith("DN/"));

        Piece returnedPiece = pieceService.findById(created.get(0).id()).orElseThrow();
        assertEquals(Piece.State.RETURNED_TO_SUPPLIER, returnedPiece.state());

        // The other three are untouched.
        assertThat(created.subList(1, 4)).allMatch(p ->
                pieceService.findById(p.id()).orElseThrow().state() == Piece.State.IN_STOCK);

        // Returning the same piece twice is refused - it is no longer IN_STOCK.
        assertThrows(IllegalStateException.class, () -> purchaseReturnService.createReturn(
                billId, List.of(created.get(0).id()), "Second attempt", LocalDate.now()));
    }

    @Test
    void supplierDuesReflectReceivedBillsMinusReturnsPlusOpeningBalance() {
        long modelId = chairModelId("APCE");
        long supplierId = supplierService.create(new Supplier(0, "Dues Test Supplier", null, null, null,
                null, null, "Maharashtra", "27", null, null, null, Money.ofRupees("500.00"), true, null));

        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 2, Money.ofRupees("1000.00"), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-004", LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);

        // A draft bill owes nothing yet.
        assertEquals(Money.ofRupees("500.00"), supplierService.dues(supplierId));

        purchaseBillService.confirmReceipt(billId);
        PurchaseBill bill = purchaseBillService.findById(billId).orElseThrow();
        assertEquals(Money.ofRupees("500.00").plus(bill.grandTotal()), supplierService.dues(supplierId));

        PurchaseLine line = purchaseBillService.linesFor(billId).get(0);
        List<Piece> created = pieceService.findByPurchaseLineId(line.id());
        purchaseReturnService.createReturn(billId, List.of(created.get(0).id()), "Damaged", LocalDate.now());

        PurchaseReturn purchaseReturn = purchaseReturnService.returnsFor(billId).get(0);
        Money expectedDues = Money.ofRupees("500.00").plus(bill.grandTotal()).minus(purchaseReturn.totalAmount());
        assertEquals(expectedDues, supplierService.dues(supplierId));
    }
}
