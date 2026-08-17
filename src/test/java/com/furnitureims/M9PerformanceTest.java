package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.money.Money;
import com.furnitureims.repository.PieceSearchCriteria;
import com.furnitureims.repository.SalesInvoiceSearchCriteria;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.ReportService;
import com.furnitureims.service.SalesInvoiceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NFR-04: performance targets "at a realistic scale of 20,000 pieces and 20,000 invoices".
 * Seeds that scale directly via batched JDBC (going through the service layer for 20,000+
 * invoices would measure billing-screen validation overhead, not the report/search queries
 * NFR-04 actually targets, and would take far too long to run in a test suite), then times
 * the operations NFR-04 names against their stated budgets.
 * <p>
 * This runs on whatever hardware executes the build, not the spec's reference "4 GB RAM,
 * spinning disk" shop PC (docs/01-requirements.md NFR-04) - there is no way to test that
 * exact hardware here. Passing here is a necessary check that catches missing indexes and
 * N+1 query patterns (the kind of bug that only shows up at scale, not in the 5-20 row
 * fixtures every other test uses); it is not a substitute for a manual timing pass on an
 * actual shop PC before go-live.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(M9PerformanceTest.TestPathsConfig.class)
class M9PerformanceTest {

    private static final int SOLD_PIECE_COUNT = 20_000;
    private static final int IN_STOCK_PIECE_COUNT = 500;
    private static final int TOTAL_PIECE_COUNT = SOLD_PIECE_COUNT + IN_STOCK_PIECE_COUNT;
    private static final int INVOICE_COUNT = SOLD_PIECE_COUNT;
    private static final int ITEM_MODEL_COUNT = 20;
    private static final int CUSTOMER_COUNT = 200;
    private static final int DATE_SPREAD_DAYS = 400;

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-m9-perf-test-"));
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ReportService reportService;
    @Autowired private PieceService pieceService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private ShopProfileRepository shopProfileRepository;

    private static boolean seeded = false;

    @BeforeAll
    static void note() {
        // Seeding happens in the first @Test method instead of here: AppPaths/DataSource are
        // Spring beans, not available in a static @BeforeAll before the context exists.
    }

    private void seedOnce() {
        if (seeded) {
            return;
        }
        shopProfileRepository.save(new ShopProfile("Perf Test Furniture Shop", null, null, null, null,
                "Maharashtra", "27", "27AAAAA0000A1Z5", ShopProfile.RegistrationType.REGULAR,
                "9999999999", null, null, null, null));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<Long> categoryIds = jdbc.queryForList("SELECT id FROM category ORDER BY id", Long.class);
            long locationId = jdbc.queryForObject(
                    "SELECT id FROM storage_location WHERE name = 'Godown'", Long.class);

            List<Long> itemModelIds = seedItemModels(categoryIds);
            List<Long> customerIds = seedCustomers();
            seedPiecesAndInvoices(itemModelIds, customerIds, locationId);
        });
        seeded = true;
    }

    private List<Long> seedItemModels(List<Long> categoryIds) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < ITEM_MODEL_COUNT; i++) {
            long categoryId = categoryIds.get(i % categoryIds.size());
            rows.add(new Object[]{"PERF-MODEL-" + i, "Perf Test Model " + i, categoryId, "9403", "18",
                    50_000_00L + i});
        }
        jdbc.batchUpdate("""
                INSERT INTO item_model (model_code, model_name, category_id, hsn_code, gst_rate, default_sale_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows);
        return jdbc.queryForList("SELECT id FROM item_model WHERE model_code LIKE 'PERF-MODEL-%' ORDER BY id",
                Long.class);
    }

    private List<Long> seedCustomers() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < CUSTOMER_COUNT; i++) {
            rows.add(new Object[]{"Perf Test Customer " + i, "90000" + String.format("%05d", i)});
        }
        jdbc.batchUpdate("INSERT INTO customer (name, phone) VALUES (?, ?)", rows);
        return jdbc.queryForList("SELECT id FROM customer WHERE name LIKE 'Perf Test Customer %' ORDER BY id",
                Long.class);
    }

    private void seedPiecesAndInvoices(List<Long> itemModelIds, List<Long> customerIds, long locationId) {
        LocalDate today = LocalDate.now();

        List<Object[]> pieceRows = new ArrayList<>();
        for (int i = 0; i < TOTAL_PIECE_COUNT; i++) {
            boolean sold = i < SOLD_PIECE_COUNT;
            long itemModelId = itemModelIds.get(i % itemModelIds.size());
            long landedCost = 400_000_00L + (i % 5000) * 100L;
            LocalDate acquiredOn = today.minusDays(i % DATE_SPREAD_DAYS);
            pieceRows.add(new Object[]{"PERF-" + String.format("%06d", i), itemModelId, "OPENING_STOCK",
                    landedCost, locationId, sold ? "SOLD" : "IN_STOCK", acquiredOn.toString()});
        }
        jdbc.batchUpdate("""
                INSERT INTO piece (tag, item_model_id, source_type, landed_cost, location_id, state, acquired_on)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, pieceRows);

        List<Long> soldPieceIds = jdbc.queryForList(
                "SELECT id FROM piece WHERE tag LIKE 'PERF-%' AND state = 'SOLD' ORDER BY id", Long.class);

        List<Object[]> invoiceRows = new ArrayList<>();
        for (int i = 0; i < INVOICE_COUNT; i++) {
            long customerId = customerIds.get(i % customerIds.size());
            LocalDate invoiceDate = today.minusDays(i % DATE_SPREAD_DAYS);
            String fy = com.furnitureims.util.FinancialYear.of(invoiceDate);
            long taxable = 5000_00L;
            long tax = 900_00L;
            long grandTotal = taxable + tax;
            invoiceRows.add(new Object[]{"PERF/" + fy + "/" + String.format("%06d", i), fy, invoiceDate.toString(),
                    customerId, "27", 0, 0, taxable, 0L, 0L, taxable, tax / 2, tax / 2, 0L, 0L, grandTotal, "ACTIVE"});
        }
        jdbc.batchUpdate("""
                INSERT INTO sales_invoice (invoice_no, financial_year, invoice_date, customer_id,
                        place_of_supply_state_code, is_interstate, is_price_inclusive, gross_value,
                        line_discount_total, bill_discount, taxable_value, cgst_amount, sgst_amount, igst_amount,
                        round_off, grand_total, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, invoiceRows);

        List<Long> invoiceIds = jdbc.queryForList(
                "SELECT id FROM sales_invoice WHERE invoice_no LIKE 'PERF/%' ORDER BY id", Long.class);

        List<Object[]> lineRows = new ArrayList<>();
        for (int i = 0; i < INVOICE_COUNT; i++) {
            long pieceId = soldPieceIds.get(i);
            long itemModelId = itemModelIds.get(i % itemModelIds.size());
            long invoiceId = invoiceIds.get(i);
            long costAtSale = 400_000_00L + (i % 5000) * 100L;
            lineRows.add(new Object[]{invoiceId, pieceId, itemModelId, "Perf Test Model", "9403", "18",
                    5000_00L, 0L, 5000_00L, 450_00L, 450_00L, 0L, 5900_00L, costAtSale});
        }
        jdbc.batchUpdate("""
                INSERT INTO sales_line (sales_invoice_id, piece_id, item_model_id, description_snapshot,
                        hsn_snapshot, gst_rate, unit_price, discount_amount, taxable_value, cgst_amount,
                        sgst_amount, igst_amount, line_total, cost_at_sale)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lineRows);
    }

    @Test
    void reportsAndSearchesCompleteWithinNfr04BudgetsAt20000PieceAnd20000InvoiceScale() {
        seedOnce();

        long stockValuationMs = time(() -> reportService.stockValuationReport(null, null, null));
        report("Stock valuation report", stockValuationMs);

        LocalDate oneYearAgo = LocalDate.now().minusYears(1);
        long salesProfitMs = time(() -> reportService.salesAndProfitReport(oneYearAgo, LocalDate.now()));
        report("Sales and profit report (1 year)", salesProfitMs);

        long duesMs = time(() -> reportService.customerDuesAging(null, null, null));
        report("Customer dues aging", duesMs);

        long dashboardMs = time(() -> reportService.dashboardSummary());
        report("Dashboard summary", dashboardMs);

        long pieceSearchMs = time(() -> pieceService.search(
                new PieceSearchCriteria(null, com.furnitureims.domain.Piece.State.IN_STOCK, null, null, null, null, null)));
        report("Piece register search", pieceSearchMs);

        long invoiceSearchMs = time(() -> salesInvoiceService.search(
                new SalesInvoiceSearchCriteria(null, null, oneYearAgo, LocalDate.now())));
        report("Invoice search (1 year)", invoiceSearchMs);

        long invoiceSaveMs = time(this::saveOneMoreInvoice);
        report("Saving one more invoice at scale", invoiceSaveMs);

        // NFR-04 budgets, generously doubled to absorb CI-hardware variance in either
        // direction - the point of this test is to catch an N+1 query pattern or a missing
        // index (which shows up as multi-second or multi-minute blowups at this scale), not
        // to pin exact millisecond figures that would make the test flaky on unrelated
        // hardware. A true "4 GB RAM, spinning disk" timing pass is manual-only (see class
        // javadoc).
        assertTrue(stockValuationMs < 10_000, "Stock valuation report took " + stockValuationMs + "ms");
        assertTrue(salesProfitMs < 10_000, "Sales and profit report took " + salesProfitMs + "ms");
        assertTrue(duesMs < 10_000, "Customer dues aging took " + duesMs + "ms");
        assertTrue(dashboardMs < 10_000, "Dashboard summary took " + dashboardMs + "ms");
        assertTrue(pieceSearchMs < 2_000, "Piece register search took " + pieceSearchMs + "ms");
        assertTrue(invoiceSearchMs < 2_000, "Invoice search took " + invoiceSearchMs + "ms");
        assertTrue(invoiceSaveMs < 4_000, "Saving an invoice took " + invoiceSaveMs + "ms");
    }

    private long saveOneMoreInvoice() {
        long extraPieceId = createExtraPiece();
        long customerId = jdbc.queryForObject(
                "SELECT id FROM customer WHERE name LIKE 'Perf Test Customer %' LIMIT 1", Long.class);
        return salesInvoiceService.createInvoice(customerId, "27", false,
                List.of(new SalesInvoiceService.InvoiceLineInput(extraPieceId, Money.ofRupees("5000.00"), Money.ZERO)),
                Money.ZERO, LocalDate.now());
    }

    private long createExtraPiece() {
        long itemModelId = jdbc.queryForObject(
                "SELECT id FROM item_model WHERE model_code LIKE 'PERF-MODEL-%' LIMIT 1", Long.class);
        long locationId = jdbc.queryForObject("SELECT id FROM storage_location WHERE name = 'Godown'", Long.class);
        String tag = "PERF-EXTRA-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO piece (tag, item_model_id, source_type, landed_cost, location_id, state, acquired_on)
                VALUES (?, ?, 'OPENING_STOCK', 400000000, ?, 'IN_STOCK', ?)
                """, tag, itemModelId, locationId, LocalDate.now().toString());
        return jdbc.queryForObject("SELECT id FROM piece WHERE tag = ?", Long.class, tag);
    }

    private static long time(Runnable operation) {
        long start = System.nanoTime();
        operation.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void report(String label, long ms) {
        System.out.println("[NFR-04] " + label + ": " + ms + " ms");
    }
}
