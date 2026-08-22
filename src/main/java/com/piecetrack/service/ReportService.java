package com.piecetrack.service;

import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.money.Money;
import com.piecetrack.repository.PieceSearchCriteria;
import com.piecetrack.repository.PieceSummary;
import com.piecetrack.repository.PurchaseBillListRow;
import com.piecetrack.repository.PurchaseBillSearchCriteria;
import com.piecetrack.repository.ReportRepository;
import com.piecetrack.repository.SalesInvoiceListRow;
import com.piecetrack.repository.SalesInvoiceSearchCriteria;
import com.piecetrack.repository.SalesProfitLineRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * FR-RPT-01..05: stock valuation/aging and sales/profit are computed from flat rows and
 * grouped in Java (the same style {@code DocumentService} already uses for invoice PDFs),
 * since a shop's realistic data volume makes that simpler and more testable than SQL-side
 * aggregation, and the underlying figures already have single sources of truth elsewhere
 * this service deliberately reuses rather than re-derives: {@link PieceSummary#daysInStock}
 * for stock age, {@link PaymentService} for every balance. A true SQL-aggregation pass is
 * explicitly left to milestone M9's performance hardening if 20,000+ piece/invoice scale
 * ever makes the Java-side approach too slow.
 */
@Service
public class ReportService {

    private final PieceService pieceService;
    private final ItemModelService itemModelService;
    private final ReportRepository reportRepository;
    private final SalesInvoiceService salesInvoiceService;
    private final PurchaseBillService purchaseBillService;
    private final SupplierService supplierService;
    private final PaymentService paymentService;

    public enum AgingBucket { DAYS_0_30, DAYS_31_60, DAYS_61_90, DAYS_90_PLUS }

    public record StockValuationRow(long categoryId, String categoryName, long itemModelId, String modelName,
                                     String modelCode, int pieceCount, Money totalLandedValue, long oldestAgeDays) {
    }

    public record AgingBucketTotals(int count0To30, Money value0To30, int count31To60, Money value31To60,
                                     int count61To90, Money value61To90, int count90Plus, Money value90Plus) {
        public int count90PlusOnly() {
            return count90Plus;
        }
    }

    public record StockValuationReport(List<StockValuationRow> rows, AgingBucketTotals aging, Money grandTotalValue,
                                        int totalPieceCount) {
    }

    public record SalesProfitAggregate(String label, long invoiceCount, Money taxableValue, Money tax,
                                        Money totalSales, Money cost, Money profit, BigDecimal marginPercent) {
    }

    public record SalesProfitReport(SalesProfitAggregate summary, List<SalesProfitAggregate> byDay,
                                     List<SalesProfitAggregate> byMonth, List<SalesProfitAggregate> byCategory,
                                     List<SalesProfitAggregate> byItemModel) {
    }

    public record CustomerDueRow(long customerId, String customerName, String customerPhone, SalesInvoice invoice,
                                  Money balance, long daysOutstanding, AgingBucket bucket) {
    }

    public record SupplierDueRow(long supplierId, String supplierName, String supplierPhone, PurchaseBill bill,
                                  Money balance, long daysOutstanding, AgingBucket bucket) {
    }

    public record DashboardSummary(Money todaySales, int todayInvoiceCount, Money monthSales, Money monthProfit,
                                    Money stockValue, int piecesInStock, int piecesAged90Plus, Money receivable,
                                    int overdueInvoiceCount, Money payable) {
    }

    public ReportService(PieceService pieceService, ItemModelService itemModelService,
                          ReportRepository reportRepository, SalesInvoiceService salesInvoiceService,
                          PurchaseBillService purchaseBillService, SupplierService supplierService,
                          PaymentService paymentService) {
        this.pieceService = pieceService;
        this.itemModelService = itemModelService;
        this.reportRepository = reportRepository;
        this.salesInvoiceService = salesInvoiceService;
        this.purchaseBillService = purchaseBillService;
        this.supplierService = supplierService;
        this.paymentService = paymentService;
    }

    public static AgingBucket bucketOf(long daysInStockOrOutstanding) {
        if (daysInStockOrOutstanding <= 30) {
            return AgingBucket.DAYS_0_30;
        }
        if (daysInStockOrOutstanding <= 60) {
            return AgingBucket.DAYS_31_60;
        }
        if (daysInStockOrOutstanding <= 90) {
            return AgingBucket.DAYS_61_90;
        }
        return AgingBucket.DAYS_90_PLUS;
    }

    // ---- Stock valuation & aging (FR-RPT-01/02) ------------------------------------------

    public StockValuationReport stockValuationReport(Long categoryId, Long locationId, AgingBucket bucketFilter) {
        List<PieceSummary> all = pieceService.search(
                new PieceSearchCriteria(null, Piece.State.IN_STOCK, null, categoryId, locationId, null, null));

        AgingBucketTotals aging = computeAgingTotals(all);

        List<PieceSummary> filtered = bucketFilter == null ? all
                : all.stream().filter(p -> bucketOf(p.daysInStock()) == bucketFilter).toList();

        record GroupKey(long itemModelId, String modelName, String modelCode, String categoryName) {
        }
        Map<GroupKey, List<PieceSummary>> byGroup = new LinkedHashMap<>();
        for (PieceSummary p : filtered) {
            GroupKey key = new GroupKey(p.piece().itemModelId(), p.modelName(), p.modelCode(), p.categoryName());
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<StockValuationRow> rows = new ArrayList<>();
        Money grandTotal = Money.ZERO;
        for (Map.Entry<GroupKey, List<PieceSummary>> entry : byGroup.entrySet()) {
            Money total = Money.ZERO;
            long oldest = 0;
            for (PieceSummary p : entry.getValue()) {
                total = total.plus(p.piece().landedCost());
                oldest = Math.max(oldest, p.daysInStock());
            }
            grandTotal = grandTotal.plus(total);
            GroupKey key = entry.getKey();
            long resolvedCategoryId = itemModelService.findById(key.itemModelId())
                    .map(ItemModel::categoryId).orElse(0L);
            rows.add(new StockValuationRow(resolvedCategoryId, key.categoryName(), key.itemModelId(),
                    key.modelName(), key.modelCode(), entry.getValue().size(), total, oldest));
        }
        rows.sort(Comparator.comparing(StockValuationRow::categoryName).thenComparing(StockValuationRow::modelName));

        return new StockValuationReport(rows, aging, grandTotal, filtered.size());
    }

    private static AgingBucketTotals computeAgingTotals(List<PieceSummary> pieces) {
        int c0 = 0;
        int c1 = 0;
        int c2 = 0;
        int c3 = 0;
        Money v0 = Money.ZERO;
        Money v1 = Money.ZERO;
        Money v2 = Money.ZERO;
        Money v3 = Money.ZERO;
        for (PieceSummary p : pieces) {
            Money cost = p.piece().landedCost();
            switch (bucketOf(p.daysInStock())) {
                case DAYS_0_30 -> {
                    c0++;
                    v0 = v0.plus(cost);
                }
                case DAYS_31_60 -> {
                    c1++;
                    v1 = v1.plus(cost);
                }
                case DAYS_61_90 -> {
                    c2++;
                    v2 = v2.plus(cost);
                }
                case DAYS_90_PLUS -> {
                    c3++;
                    v3 = v3.plus(cost);
                }
            }
        }
        return new AgingBucketTotals(c0, v0, c1, v1, c2, v2, c3, v3);
    }

    // ---- Sales & profit (FR-RPT-03) -------------------------------------------------------

    public SalesProfitReport salesAndProfitReport(LocalDate from, LocalDate to) {
        List<SalesProfitLineRow> lines = reportRepository.salesLinesInPeriod(from, to);

        SalesProfitAggregate summary = aggregate("Summary", lines);
        List<SalesProfitAggregate> byDay = groupAndAggregate(lines, l -> l.invoiceDate().toString(),
                l -> l.invoiceDate().toString(), Comparator.comparing(SalesProfitAggregate::label));
        List<SalesProfitAggregate> byMonth = groupAndAggregate(lines, l -> YearMonth.from(l.invoiceDate()),
                l -> YearMonth.from(l.invoiceDate()).toString(), Comparator.comparing(SalesProfitAggregate::label));
        // Grouped by id, not by name: category names are unique at the DB level but item
        // model names are not (only model_code is), so keying on the display name would
        // silently merge two different models that happen to share one.
        List<SalesProfitAggregate> byCategory = groupAndAggregate(lines, SalesProfitLineRow::categoryId,
                SalesProfitLineRow::categoryName, Comparator.comparing(SalesProfitAggregate::profit).reversed());
        List<SalesProfitAggregate> byItemModel = groupAndAggregate(lines, SalesProfitLineRow::itemModelId,
                SalesProfitLineRow::modelName, Comparator.comparing(SalesProfitAggregate::profit).reversed());

        return new SalesProfitReport(summary, byDay, byMonth, byCategory, byItemModel);
    }

    private static <K> List<SalesProfitAggregate> groupAndAggregate(List<SalesProfitLineRow> lines,
                                                                      Function<SalesProfitLineRow, K> keyFn,
                                                                      Function<SalesProfitLineRow, String> labelFn,
                                                                      Comparator<SalesProfitAggregate> order) {
        Map<K, List<SalesProfitLineRow>> byKey = new LinkedHashMap<>();
        Map<K, String> labelByKey = new LinkedHashMap<>();
        for (SalesProfitLineRow line : lines) {
            K key = keyFn.apply(line);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
            labelByKey.putIfAbsent(key, labelFn.apply(line));
        }
        List<SalesProfitAggregate> result = new ArrayList<>();
        for (Map.Entry<K, List<SalesProfitLineRow>> entry : byKey.entrySet()) {
            result.add(aggregate(labelByKey.get(entry.getKey()), entry.getValue()));
        }
        result.sort(order);
        return result;
    }

    private static SalesProfitAggregate aggregate(String label, List<SalesProfitLineRow> lines) {
        Money taxable = Money.ZERO;
        Money tax = Money.ZERO;
        Money cost = Money.ZERO;
        long invoiceCount = lines.stream().map(SalesProfitLineRow::invoiceId).distinct().count();
        for (SalesProfitLineRow l : lines) {
            taxable = taxable.plus(l.taxableValue());
            tax = tax.plus(l.cgstAmount()).plus(l.sgstAmount()).plus(l.igstAmount());
            cost = cost.plus(l.costAtSale());
        }
        Money totalSales = taxable.plus(tax);
        Money profit = taxable.minus(cost);
        BigDecimal margin = taxable.isZero() ? BigDecimal.ZERO
                : profit.rupees().multiply(BigDecimal.valueOf(100))
                        .divide(taxable.rupees(), 2, RoundingMode.HALF_EVEN);
        return new SalesProfitAggregate(label, invoiceCount, taxable, tax, totalSales, cost, profit, margin);
    }

    // ---- Outstanding dues & aging (FR-RPT-04) ---------------------------------------------

    public List<CustomerDueRow> customerDuesAging(Long customerId, AgingBucket bucketFilter, Money minimumAmount) {
        List<SalesInvoiceListRow> invoices = salesInvoiceService.search(
                new SalesInvoiceSearchCriteria(customerId, SalesInvoice.Status.ACTIVE, null, null));
        // Bulk balance lookup (two aggregate queries total), not one invoiceBalance() call
        // per row - at 20,000-invoice scale the per-row version blew well past NFR-04's
        // budget (caught by M9's performance test), since each call did two more queries
        // of its own.
        Map<Long, Money> balances = paymentService.invoiceBalances(
                invoices.stream().map(SalesInvoiceListRow::invoice).toList());
        List<CustomerDueRow> result = new ArrayList<>();
        for (SalesInvoiceListRow row : invoices) {
            Money balance = balances.get(row.invoice().id());
            if (!balance.isPositive()) {
                continue;
            }
            if (minimumAmount != null && balance.paisa() < minimumAmount.paisa()) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(row.invoice().invoiceDate(), LocalDate.now());
            AgingBucket bucket = bucketOf(days);
            if (bucketFilter != null && bucket != bucketFilter) {
                continue;
            }
            result.add(new CustomerDueRow(row.invoice().customerId(), row.customerName(), row.customerPhone(),
                    row.invoice(), balance, days, bucket));
        }
        result.sort(Comparator.comparingLong(CustomerDueRow::daysOutstanding).reversed());
        return result;
    }

    public List<SupplierDueRow> supplierDuesAging(Long supplierId, AgingBucket bucketFilter, Money minimumAmount) {
        List<PurchaseBillListRow> bills = purchaseBillService.search(
                new PurchaseBillSearchCriteria(supplierId, PurchaseBill.Status.RECEIVED, null, null));
        // Bulk balance lookup - see the identical note in customerDuesAging.
        Map<Long, Money> balances = paymentService.purchaseBillBalances(
                bills.stream().map(PurchaseBillListRow::bill).toList());
        List<SupplierDueRow> result = new ArrayList<>();
        for (PurchaseBillListRow row : bills) {
            Money balance = balances.get(row.bill().id());
            if (!balance.isPositive()) {
                continue;
            }
            if (minimumAmount != null && balance.paisa() < minimumAmount.paisa()) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(row.bill().billDate(), LocalDate.now());
            AgingBucket bucket = bucketOf(days);
            if (bucketFilter != null && bucket != bucketFilter) {
                continue;
            }
            String phone = supplierService.findById(row.bill().supplierId()).map(s -> s.phone()).orElse(null);
            result.add(new SupplierDueRow(row.bill().supplierId(), row.supplierName(), phone, row.bill(), balance,
                    days, bucket));
        }
        result.sort(Comparator.comparingLong(SupplierDueRow::daysOutstanding).reversed());
        return result;
    }

    // ---- Dashboard (FR-RPT-05) -------------------------------------------------------------

    public DashboardSummary dashboardSummary() {
        LocalDate today = LocalDate.now();

        List<SalesInvoiceListRow> todayInvoices = salesInvoiceService.search(
                new SalesInvoiceSearchCriteria(null, SalesInvoice.Status.ACTIVE, today, today));
        Money todaySales = Money.ZERO;
        for (SalesInvoiceListRow row : todayInvoices) {
            todaySales = todaySales.plus(row.invoice().grandTotal());
        }

        SalesProfitReport monthReport = salesAndProfitReport(today.withDayOfMonth(1), today);

        StockValuationReport stock = stockValuationReport(null, null, null);

        List<CustomerDueRow> customerDues = customerDuesAging(null, null, null);
        Money receivable = Money.ZERO;
        for (CustomerDueRow row : customerDues) {
            receivable = receivable.plus(row.balance());
        }

        List<SupplierDueRow> supplierDues = supplierDuesAging(null, null, null);
        Money payable = Money.ZERO;
        for (SupplierDueRow row : supplierDues) {
            payable = payable.plus(row.balance());
        }

        return new DashboardSummary(todaySales, todayInvoices.size(), monthReport.summary().totalSales(),
                monthReport.summary().profit(), stock.grandTotalValue(), stock.totalPieceCount(),
                stock.aging().count90PlusOnly(), receivable, customerDues.size(), payable);
    }
}
