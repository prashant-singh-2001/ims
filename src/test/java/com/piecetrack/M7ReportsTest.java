package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.Customer;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.domain.SalesReturn;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.CustomerService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.PurchaseBillService;
import com.piecetrack.service.ReportService;
import com.piecetrack.service.SalesInvoiceService;
import com.piecetrack.service.SalesReturnService;
import com.piecetrack.service.StorageLocationService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises milestone M7's reports against a real temp-directory SQLite database - the
 * riskiest arithmetic here is FR-RPT-03's return-netting (a sold-then-returned piece must
 * not still count as "sold" in a period profit figure) and the aging-bucket boundaries for
 * both stock (FR-RPT-02) and dues (FR-RPT-04), so those get real assertions rather than a
 * smoke check.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(M7ReportsTest.TestPathsConfig.class)
class M7ReportsTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("piecetrack-m7-test-"));
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
    @Autowired private ReportService reportService;
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

    /** The model name embeds {@code code} so "by item model" report lookups in these tests
     *  can find their own row unambiguously - {@code @SpringBootTest} shares one database
     *  across every test method here, and item_model.model_name (unlike model_code) has no
     *  DB-level uniqueness constraint, so a shared literal name would leave two different
     *  tests' models indistinguishable by label. */
    private long chairModelId(String code) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Other")).findFirst().orElseThrow().id();
        return itemModelService.create(new ItemModel(0, code, "Test Widget " + code, categoryId, "9403",
                new BigDecimal("18"), null, true, null));
    }

    private Piece newPiece(long modelId, String cost, LocalDate acquiredOn) {
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst()
                .map(l -> l.id())
                .orElseGet(() -> storageLocationService.create("Godown"));
        return pieceService.createOpeningStock(modelId, 1, Money.ofRupees(cost), locationId, acquiredOn).get(0);
    }

    private long customerId(String name, String phone) {
        return customerService.create(
                new Customer(0, name, phone, null, null, null, null, null, null, null, null, null));
    }

    /** {@code @SpringBootTest} shares one database across every test method in this class
     *  (the same gotcha M4SalesTest's invoice-sequence tests hit), and the aging panel is a
     *  shop-wide aggregate with no per-test scope to filter by - so this asserts the
     *  *change* in each bucket against a baseline captured before this test's pieces exist,
     *  never an assumed-empty absolute count. */
    @Test
    void stockValuationGroupsByModelAndBucketsByAge() {
        ReportService.AgingBucketTotals baseline = reportService.stockValuationReport(null, null, null).aging();

        long modelId = chairModelId("RPTA");
        LocalDate today = LocalDate.now();
        newPiece(modelId, "1000.00", today);
        newPiece(modelId, "1000.00", today);
        newPiece(modelId, "2000.00", today.minusDays(45));
        newPiece(modelId, "3000.00", today.minusDays(100));

        ReportService.StockValuationReport report = reportService.stockValuationReport(null, null, null);
        ReportService.StockValuationRow row = report.rows().stream()
                .filter(r -> r.modelCode().equals("RPTA")).findFirst().orElseThrow();
        assertEquals(4, row.pieceCount());
        assertEquals(Money.ofRupees("7000.00"), row.totalLandedValue());
        assertEquals(100, row.oldestAgeDays());

        ReportService.AgingBucketTotals aging = report.aging();
        assertEquals(baseline.count0To30() + 2, aging.count0To30());
        assertEquals(baseline.value0To30().plus(Money.ofRupees("2000.00")), aging.value0To30());
        assertEquals(baseline.count31To60() + 1, aging.count31To60());
        assertEquals(baseline.value31To60().plus(Money.ofRupees("2000.00")), aging.value31To60());
        assertEquals(baseline.count90Plus() + 1, aging.count90Plus());
        assertEquals(baseline.value90Plus().plus(Money.ofRupees("3000.00")), aging.value90Plus());

        ReportService.StockValuationReport filtered = reportService.stockValuationReport(
                null, null, ReportService.AgingBucket.DAYS_90_PLUS);
        ReportService.StockValuationRow filteredRow = filtered.rows().stream()
                .filter(r -> r.modelCode().equals("RPTA")).findFirst().orElseThrow();
        assertEquals(1, filteredRow.pieceCount());
        assertEquals(Money.ofRupees("3000.00"), filteredRow.totalLandedValue());
    }

    /** Pinned to a fixed historical date, not {@code LocalDate.now()}: this test's report
     *  query window must contain exactly this test's own data and nothing another test
     *  method happens to also date "today", since all methods here share one database. */
    @Test
    void salesProfitNetsOutReturnedLines() {
        long modelId = chairModelId("RPTB");
        LocalDate saleDate = LocalDate.of(2024, 6, 15);
        Piece keptPiece = newPiece(modelId, "2000.00", saleDate);
        Piece returnedPiece = newPiece(modelId, "2000.00", saleDate);
        long customerId = customerId("Report Customer", "9000000001");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(keptPiece.id(), Money.ofRupees("5000.00"), Money.ZERO),
                new SalesInvoiceService.InvoiceLineInput(returnedPiece.id(), Money.ofRupees("5000.00"), Money.ZERO));
        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO, saleDate);

        salesReturnService.createReturn(invoiceId, List.of(returnedPiece.id()), "Wrong colour", saleDate,
                SalesReturn.RefundMode.CASH_REFUND);

        ReportService.SalesProfitReport report = reportService.salesAndProfitReport(saleDate, saleDate);
        ReportService.SalesProfitAggregate summary = report.summary();

        // Only the kept piece should count: taxable 5000, cost 2000, profit 3000.
        assertEquals(1, summary.invoiceCount());
        assertEquals(Money.ofRupees("5000.00"), summary.taxableValue());
        assertEquals(Money.ofRupees("2000.00"), summary.cost());
        assertEquals(Money.ofRupees("3000.00"), summary.profit());

        ReportService.SalesProfitAggregate byModel = report.byItemModel().stream()
                .filter(a -> a.label().equals("Test Widget RPTB")).findFirst().orElseThrow();
        assertEquals(Money.ofRupees("5000.00"), byModel.taxableValue());
        assertEquals(Money.ofRupees("2000.00"), byModel.cost());
    }

    @Test
    void customerDuesAgeCorrectlyAndFilterByBucketAndMinimumAmount() {
        long modelId = chairModelId("RPTC");
        LocalDate oldDate = LocalDate.now().minusDays(100);
        Piece piece = newPiece(modelId, "1000.00", oldDate);
        long customerId = customerId("Overdue Customer", "9000000002");

        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, oldDate);

        List<ReportService.CustomerDueRow> all = reportService.customerDuesAging(null, null, null);
        ReportService.CustomerDueRow due = all.stream()
                .filter(r -> r.invoice().id() == invoiceId).findFirst().orElseThrow();
        assertEquals(ReportService.AgingBucket.DAYS_90_PLUS, due.bucket());
        assertTrue(due.daysOutstanding() >= 100);

        List<ReportService.CustomerDueRow> matchingBucket = reportService.customerDuesAging(
                null, ReportService.AgingBucket.DAYS_90_PLUS, null);
        assertTrue(matchingBucket.stream().anyMatch(r -> r.invoice().id() == invoiceId));

        List<ReportService.CustomerDueRow> wrongBucket = reportService.customerDuesAging(
                null, ReportService.AgingBucket.DAYS_0_30, null);
        assertTrue(wrongBucket.stream().noneMatch(r -> r.invoice().id() == invoiceId));

        List<ReportService.CustomerDueRow> tooHighMinimum = reportService.customerDuesAging(
                null, null, Money.ofRupees("999999.00"));
        assertTrue(tooHighMinimum.stream().noneMatch(r -> r.invoice().id() == invoiceId));
    }

    @Test
    void supplierDuesReflectReceivedBillBalance() {
        long modelId = chairModelId("RPTD");
        long supplierId = supplierService.create(new Supplier(0, "Report Supplier", null, null, null,
                null, null, "Maharashtra", "27", null, null, null, Money.ZERO, true, null));
        LocalDate oldDate = LocalDate.now().minusDays(40);

        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 1, Money.ofRupees("1000.00"), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "RPT-BILL-1", oldDate, oldDate,
                Money.ZERO, Money.ZERO, Money.ZERO, null, lines);
        purchaseBillService.confirmReceipt(billId);

        List<ReportService.SupplierDueRow> dues = reportService.supplierDuesAging(null, null, null);
        ReportService.SupplierDueRow due = dues.stream()
                .filter(r -> r.bill().id() == billId).findFirst().orElseThrow();
        assertEquals(ReportService.AgingBucket.DAYS_31_60, due.bucket());
        assertEquals("Report Supplier", due.supplierName());

        PurchaseBill bill = purchaseBillService.findById(billId).orElseThrow();
        assertEquals(bill.grandTotal(), due.balance());
    }

    @Test
    void dashboardSummaryReflectsTodaysSale() {
        long modelId = chairModelId("RPTE");
        Piece piece = newPiece(modelId, "1000.00", LocalDate.now());
        long customerId = customerId("Dashboard Customer", "9000000003");

        salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("2000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());

        ReportService.DashboardSummary summary = reportService.dashboardSummary();
        assertTrue(summary.todayInvoiceCount() >= 1);
        assertTrue(summary.todaySales().paisa() >= Money.ofRupees("2000.00").paisa());
    }
}
