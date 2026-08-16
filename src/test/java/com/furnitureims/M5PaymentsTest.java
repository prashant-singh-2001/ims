package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Payment;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.PurchaseBill;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.PaymentSearchCriteria;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PaymentService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.PurchaseBillService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.StorageLocationService;
import com.furnitureims.service.SupplierService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises milestone M5's payments against a real temp-directory SQLite database -
 * balance computation (FR-PAY-03), overpayment rejection (FR-PAY-04), soft-deletion
 * restoring a balance (FR-PAY-06), advance-at-billing (FR-PAY-01) and invoice-cancellation
 * reversing allocations (FR-SAL-11) are exactly the arithmetic and bookkeeping worth a real
 * assertion, not a mock.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(M5PaymentsTest.TestPathsConfig.class)
class M5PaymentsTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-m5-test-"));
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
    @Autowired private PaymentService paymentService;
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
        return customerService.create(new Customer(0, name, phone, null, null, null, null, null, null, null, null));
    }

    private long supplierId(String name) {
        return supplierService.create(new Supplier(0, name, null, null, null, null, null,
                "Maharashtra", "27", null, null, null, Money.ZERO, true, null));
    }

    private long invoiceFor(long customerId, Piece piece, String price) {
        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees(price), Money.ZERO));
        return salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO, LocalDate.now());
    }

    private long receivedBillFor(long supplierId, long modelId, String price) {
        List<PurchaseBillService.LineInput> lines = List.of(
                new PurchaseBillService.LineInput(modelId, 1, Money.ofRupees(price), Money.ZERO,
                        new BigDecimal("18")));
        long billId = purchaseBillService.saveDraft(null, supplierId, "BILL-" + billIdCounter++, LocalDate.now(),
                LocalDate.now(), Money.ZERO, Money.ZERO, Money.ZERO, null, lines);
        purchaseBillService.confirmReceipt(billId);
        return billId;
    }

    private int billIdCounter = 1;

    @Test
    void partialThenFullCustomerPaymentReducesInvoiceBalanceToZero() {
        long modelId = chairModelId("PAYA");
        Piece piece = newPiece(modelId, "2000.00");
        long customerId = customerId("Ravi Kumar", "9000000001");
        long invoiceId = invoiceFor(customerId, piece, "5000.00");
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        assertEquals(invoice.grandTotal(), paymentService.invoiceBalance(invoiceId));

        paymentService.recordCustomerPayment(customerId, Money.ofRupees("2000.00"), Payment.Mode.CASH, null,
                null, LocalDate.now(), Map.of(invoiceId, Money.ofRupees("2000.00")));
        assertEquals(invoice.grandTotal().minus(Money.ofRupees("2000.00")), paymentService.invoiceBalance(invoiceId));

        Money remaining = paymentService.invoiceBalance(invoiceId);
        paymentService.recordCustomerPayment(customerId, remaining, Payment.Mode.UPI, "UPI-REF-1", null,
                LocalDate.now(), Map.of(invoiceId, remaining));
        assertEquals(Money.ZERO, paymentService.invoiceBalance(invoiceId));
        assertTrue(paymentService.unpaidInvoicesFor(customerId).isEmpty());
    }

    @Test
    void allocationExceedingInvoiceBalanceIsRejected() {
        long modelId = chairModelId("PAYB");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Over Payer", "9000000002");
        long invoiceId = invoiceFor(customerId, piece, "2000.00");
        Money balance = paymentService.invoiceBalance(invoiceId);

        assertThrows(IllegalArgumentException.class, () -> paymentService.recordCustomerPayment(
                customerId, balance.plus(Money.ofRupees("1.00")), Payment.Mode.CASH, null, null,
                LocalDate.now(), Map.of(invoiceId, balance.plus(Money.ofRupees("1.00")))));

        assertThrows(IllegalArgumentException.class, () -> paymentService.recordCustomerPayment(
                customerId, balance, Payment.Mode.CASH, null, null,
                LocalDate.now(), Map.of(invoiceId, balance.plus(Money.ofRupees("500.00")))),
                "an allocation total above the payment amount must also be rejected");
    }

    @Test
    void unallocatedOnAccountPaymentIsRecordedWithoutTouchingAnyInvoice() {
        long modelId = chairModelId("PAYC");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("On Account", "9000000003");
        long invoiceId = invoiceFor(customerId, piece, "2000.00");
        Money balanceBefore = paymentService.invoiceBalance(invoiceId);

        long paymentId = paymentService.recordCustomerPayment(customerId, Money.ofRupees("500.00"),
                Payment.Mode.CASH, null, "on account", LocalDate.now(), Map.of());

        assertEquals(balanceBefore, paymentService.invoiceBalance(invoiceId));
        assertTrue(paymentService.allocationsFor(paymentId).isEmpty());
    }

    @Test
    void deletingAPaymentRestoresTheBalanceItHadReduced() {
        long modelId = chairModelId("PAYD");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Delete Test", "9000000004");
        long invoiceId = invoiceFor(customerId, piece, "3000.00");
        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();

        long paymentId = paymentService.recordCustomerPayment(customerId, Money.ofRupees("1000.00"),
                Payment.Mode.CASH, null, null, LocalDate.now(), Map.of(invoiceId, Money.ofRupees("1000.00")));
        assertEquals(invoice.grandTotal().minus(Money.ofRupees("1000.00")), paymentService.invoiceBalance(invoiceId));

        assertThrows(IllegalArgumentException.class, () -> paymentService.deletePayment(paymentId, "  "));

        paymentService.deletePayment(paymentId, "Recorded against the wrong customer");
        assertEquals(invoice.grandTotal(), paymentService.invoiceBalance(invoiceId));
        assertTrue(paymentService.findById(paymentId).orElseThrow().deleted());

        assertThrows(IllegalStateException.class,
                () -> paymentService.deletePayment(paymentId, "Again"));
    }

    @Test
    void supplierPaymentReducesBillBalanceAndOverallDues() {
        long modelId = chairModelId("PAYE");
        long supplierId = supplierId("Dues Supplier");
        long billId = receivedBillFor(supplierId, modelId, "1000.00");
        PurchaseBill bill = purchaseBillService.findById(billId).orElseThrow();

        assertEquals(bill.grandTotal(), paymentService.purchaseBillBalance(billId));
        assertEquals(bill.grandTotal(), supplierService.dues(supplierId));

        Money half = Money.ofRupees(bill.grandTotal().rupees().divide(BigDecimal.valueOf(2)));
        paymentService.recordSupplierPayment(supplierId, half, Payment.Mode.BANK_TRANSFER, "TXN-1", null,
                LocalDate.now(), Map.of(billId, half));

        assertEquals(bill.grandTotal().minus(half), paymentService.purchaseBillBalance(billId));
        assertEquals(bill.grandTotal().minus(half), supplierService.dues(supplierId));
        assertEquals(1, paymentService.unpaidBillsFor(supplierId).size());
    }

    @Test
    void cancellingAnInvoiceReversesItsPaymentAllocationsButKeepsThePaymentItself() {
        long modelId = chairModelId("PAYF");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Cancel With Payment", "9000000005");
        long invoiceId = invoiceFor(customerId, piece, "2000.00");

        long paymentId = paymentService.recordCustomerPayment(customerId, Money.ofRupees("500.00"),
                Payment.Mode.CASH, null, null, LocalDate.now(), Map.of(invoiceId, Money.ofRupees("500.00")));
        assertFalse(paymentService.allocationsFor(paymentId).isEmpty());

        salesInvoiceService.cancelInvoice(invoiceId, "Customer changed their mind");

        assertTrue(paymentService.allocationsFor(paymentId).isEmpty(),
                "FR-SAL-11: the allocation link must be removed on cancellation");
        assertEquals(Money.ZERO, paymentService.invoiceBalance(invoiceId));
        assertFalse(paymentService.findById(paymentId).orElseThrow().deleted(),
                "the payment itself represents real money received and must not be deleted");
    }

    @Test
    void advanceAtBillingRecordsAnAllocatedPaymentAndReducesBalanceImmediately() {
        long modelId = chairModelId("PAYG");
        Piece piece = newPiece(modelId, "2000.00");
        long customerId = customerId("Advance Payer", "9000000006");

        List<SalesInvoiceService.InvoiceLineInput> lines = List.of(
                new SalesInvoiceService.InvoiceLineInput(piece.id(), Money.ofRupees("5000.00"), Money.ZERO));
        long invoiceId = salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO,
                LocalDate.now(), Money.ofRupees("1000.00"), Payment.Mode.CASH, null, "advance at billing");

        SalesInvoice invoice = salesInvoiceService.findById(invoiceId).orElseThrow();
        assertEquals(invoice.grandTotal().minus(Money.ofRupees("1000.00")), paymentService.invoiceBalance(invoiceId));
        assertEquals(invoice.grandTotal().minus(Money.ofRupees("1000.00")), salesInvoiceService.balance(invoice));

        List<Payment> payments = paymentService.search(PaymentSearchCriteria.empty());
        assertTrue(payments.stream().anyMatch(p -> p.partyId() == customerId
                && p.amount().equals(Money.ofRupees("1000.00"))));
    }

    @Test
    void aPaymentCanBeSplitAcrossMultipleInvoices() {
        long modelId = chairModelId("PAYH");
        Piece pieceA = newPiece(modelId, "1000.00");
        Piece pieceB = newPiece(modelId, "1000.00");
        long customerId = customerId("Split Payer", "9000000007");
        long invoiceIdA = invoiceFor(customerId, pieceA, "2000.00");
        long invoiceIdB = invoiceFor(customerId, pieceB, "3000.00");

        paymentService.recordCustomerPayment(customerId, Money.ofRupees("2500.00"), Payment.Mode.CASH, null,
                null, LocalDate.now(),
                Map.of(invoiceIdA, Money.ofRupees("1000.00"), invoiceIdB, Money.ofRupees("1500.00")));

        SalesInvoice invoiceA = salesInvoiceService.findById(invoiceIdA).orElseThrow();
        SalesInvoice invoiceB = salesInvoiceService.findById(invoiceIdB).orElseThrow();
        assertEquals(invoiceA.grandTotal().minus(Money.ofRupees("1000.00")), paymentService.invoiceBalance(invoiceIdA));
        assertEquals(invoiceB.grandTotal().minus(Money.ofRupees("1500.00")), paymentService.invoiceBalance(invoiceIdB));
    }
}
