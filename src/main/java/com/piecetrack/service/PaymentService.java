package com.piecetrack.service;

import com.piecetrack.domain.Payment;
import com.piecetrack.domain.PaymentAllocation;
import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.domain.PurchaseReturn;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.domain.SalesReturn;
import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;
import com.piecetrack.repository.PaymentAllocationRepository;
import com.piecetrack.repository.PaymentRepository;
import com.piecetrack.repository.PaymentSearchCriteria;
import com.piecetrack.repository.PurchaseBillListRow;
import com.piecetrack.repository.PurchaseBillRepository;
import com.piecetrack.repository.PurchaseBillSearchCriteria;
import com.piecetrack.repository.PurchaseReturnRepository;
import com.piecetrack.repository.SalesInvoiceListRow;
import com.piecetrack.repository.SalesInvoiceRepository;
import com.piecetrack.repository.SalesInvoiceSearchCriteria;
import com.piecetrack.repository.SalesReturnRepository;
import com.piecetrack.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for every balance in the system (FR-PAY-01..06) - "computed,
 * never stored", per docs/02-data-model.md section 4.6. Deliberately depends only on
 * repositories, never on {@code SalesInvoiceService} or {@code PurchaseBillService}
 * directly: both of those need to call into this service (billing's advance payment,
 * cancellation's payment reversal), and a dependency back from here to them would create a
 * circular bean graph Spring cannot construct.
 */
@Service
public class PaymentService {

    public record InvoiceBalanceRow(SalesInvoice invoice, Money balance) {
    }

    public record BillBalanceRow(PurchaseBill bill, Money balance) {
    }

    private final PaymentRepository paymentRepository;
    private final PaymentAllocationRepository paymentAllocationRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final PurchaseBillRepository purchaseBillRepository;
    private final PurchaseReturnRepository purchaseReturnRepository;
    private final SupplierRepository supplierRepository;
    private final AuditLogService auditLogService;

    public PaymentService(PaymentRepository paymentRepository, PaymentAllocationRepository paymentAllocationRepository,
                           SalesInvoiceRepository salesInvoiceRepository, SalesReturnRepository salesReturnRepository,
                           PurchaseBillRepository purchaseBillRepository, PurchaseReturnRepository purchaseReturnRepository,
                           SupplierRepository supplierRepository, AuditLogService auditLogService) {
        this.paymentRepository = paymentRepository;
        this.paymentAllocationRepository = paymentAllocationRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesReturnRepository = salesReturnRepository;
        this.purchaseBillRepository = purchaseBillRepository;
        this.purchaseReturnRepository = purchaseReturnRepository;
        this.supplierRepository = supplierRepository;
        this.auditLogService = auditLogService;
    }

    // ---- Balances (FR-PAY-03) --------------------------------------------------------

    /** grand_total - credit notes - allocated payments; zero for a cancelled invoice. */
    public Money invoiceBalance(long salesInvoiceId) {
        SalesInvoice invoice = salesInvoiceRepository.findById(salesInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
        if (invoice.status() != SalesInvoice.Status.ACTIVE) {
            return Money.ZERO;
        }
        Money creditNotes = Money.ZERO;
        for (SalesReturn r : salesReturnRepository.findBySalesInvoiceId(salesInvoiceId)) {
            creditNotes = creditNotes.plus(r.totalAmount());
        }
        Money paid = paymentAllocationRepository.sumByTarget(PaymentAllocation.TargetType.SALES_INVOICE, salesInvoiceId);
        return invoice.grandTotal().minus(creditNotes).minus(paid);
    }

    /** grand_total - debit notes - allocated payments; zero unless the bill is RECEIVED. */
    public Money purchaseBillBalance(long purchaseBillId) {
        PurchaseBill bill = purchaseBillRepository.findById(purchaseBillId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase bill not found."));
        if (bill.status() != PurchaseBill.Status.RECEIVED) {
            return Money.ZERO;
        }
        Money debitNotes = Money.ZERO;
        for (PurchaseReturn r : purchaseReturnRepository.findByPurchaseBillId(purchaseBillId)) {
            debitNotes = debitNotes.plus(r.totalAmount());
        }
        Money paid = paymentAllocationRepository.sumByTarget(PaymentAllocation.TargetType.PURCHASE_BILL, purchaseBillId);
        return bill.grandTotal().minus(debitNotes).minus(paid);
    }

    /** Opening balance plus the balance of every RECEIVED bill from this supplier. */
    public Money supplierDues(long supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found."));
        Money total = supplier.openingBalance();
        for (PurchaseBillListRow row : purchaseBillRepository.search(
                new PurchaseBillSearchCriteria(supplierId, PurchaseBill.Status.RECEIVED, null, null))) {
            total = total.plus(purchaseBillBalance(row.bill().id()));
        }
        return total;
    }

    /** The sum of every active invoice's balance for this customer. */
    public Money customerDues(long customerId) {
        Money total = Money.ZERO;
        for (SalesInvoiceListRow row : salesInvoiceRepository.search(
                new SalesInvoiceSearchCriteria(customerId, SalesInvoice.Status.ACTIVE, null, null))) {
            total = total.plus(invoiceBalance(row.invoice().id()));
        }
        return total;
    }

    public List<InvoiceBalanceRow> unpaidInvoicesFor(long customerId) {
        return salesInvoiceRepository.search(new SalesInvoiceSearchCriteria(customerId, SalesInvoice.Status.ACTIVE, null, null))
                .stream()
                .map(row -> new InvoiceBalanceRow(row.invoice(), invoiceBalance(row.invoice().id())))
                .filter(r -> r.balance().isPositive())
                .toList();
    }

    /** Bulk equivalent of calling {@link #invoiceBalance} once per invoice - two aggregate
     *  queries total instead of two per invoice. Every invoice passed in is assumed ACTIVE
     *  already (callers filter that themselves, same as the searches {@link #invoiceBalance}
     *  itself is built from), so unlike the single-invoice version this does not re-check
     *  status. Exists specifically so FR-RPT-04's dues report stays within its NFR-04 budget
     *  at 20,000-invoice scale - see M9's performance test, which is what caught the N+1
     *  pattern this replaces. */
    public Map<Long, Money> invoiceBalances(List<SalesInvoice> activeInvoices) {
        Map<Long, Money> creditNotesByInvoice = salesReturnRepository.sumTotalAmountByInvoiceId();
        Map<Long, Money> paidByInvoice = paymentAllocationRepository.sumByTargetType(
                PaymentAllocation.TargetType.SALES_INVOICE);
        Map<Long, Money> result = new LinkedHashMap<>();
        for (SalesInvoice invoice : activeInvoices) {
            Money creditNotes = creditNotesByInvoice.getOrDefault(invoice.id(), Money.ZERO);
            Money paid = paidByInvoice.getOrDefault(invoice.id(), Money.ZERO);
            result.put(invoice.id(), invoice.grandTotal().minus(creditNotes).minus(paid));
        }
        return result;
    }

    /** Bulk equivalent of {@link #purchaseBillBalance} - see {@link #invoiceBalances} for why. */
    public Map<Long, Money> purchaseBillBalances(List<PurchaseBill> receivedBills) {
        Map<Long, Money> debitNotesByBill = purchaseReturnRepository.sumTotalAmountByBillId();
        Map<Long, Money> paidByBill = paymentAllocationRepository.sumByTargetType(
                PaymentAllocation.TargetType.PURCHASE_BILL);
        Map<Long, Money> result = new LinkedHashMap<>();
        for (PurchaseBill bill : receivedBills) {
            Money debitNotes = debitNotesByBill.getOrDefault(bill.id(), Money.ZERO);
            Money paid = paidByBill.getOrDefault(bill.id(), Money.ZERO);
            result.put(bill.id(), bill.grandTotal().minus(debitNotes).minus(paid));
        }
        return result;
    }

    public List<BillBalanceRow> unpaidBillsFor(long supplierId) {
        return purchaseBillRepository.search(new PurchaseBillSearchCriteria(supplierId, PurchaseBill.Status.RECEIVED, null, null))
                .stream()
                .map(row -> new BillBalanceRow(row.bill(), purchaseBillBalance(row.bill().id())))
                .filter(r -> r.balance().isPositive())
                .toList();
    }

    // ---- Recording (FR-PAY-01/02/05) -------------------------------------------------

    /** @param invoiceAllocations invoice id -> amount to allocate to it; may be empty (an
     *                            unallocated payment on account) but must not sum to more
     *                            than {@code amount}, and no single allocation may exceed
     *                            that invoice's own outstanding balance (FR-PAY-04). */
    @Transactional
    public long recordCustomerPayment(long customerId, Money amount, Payment.Mode mode, String referenceNo,
                                       String note, LocalDate paymentDate, Map<Long, Money> invoiceAllocations) {
        validateAmountAndAllocations(amount, invoiceAllocations);
        if (invoiceAllocations != null) {
            for (Map.Entry<Long, Money> entry : invoiceAllocations.entrySet()) {
                Money balance = invoiceBalance(entry.getKey());
                if (entry.getValue().paisa() > balance.paisa()) {
                    throw new IllegalArgumentException("Allocation exceeds that invoice's outstanding balance of "
                            + balance.toDisplayString() + ".");
                }
            }
        }

        Payment payment = new Payment(0, Payment.Direction.IN, Payment.PartyType.CUSTOMER, customerId, paymentDate,
                amount, mode, referenceNo, note, false, null, null);
        long paymentId = paymentRepository.create(payment);
        if (invoiceAllocations != null) {
            for (Map.Entry<Long, Money> entry : invoiceAllocations.entrySet()) {
                paymentAllocationRepository.create(new PaymentAllocation(0, paymentId,
                        PaymentAllocation.TargetType.SALES_INVOICE, entry.getKey(), entry.getValue()));
            }
        }
        auditLogService.record("PAYMENT_RECORDED", "PAYMENT", paymentId, null,
                Map.of("direction", "IN", "customerId", customerId, "amount", amount.toDisplayString(),
                        "mode", mode.name()));
        return paymentId;
    }

    @Transactional
    public long recordSupplierPayment(long supplierId, Money amount, Payment.Mode mode, String referenceNo,
                                       String note, LocalDate paymentDate, Map<Long, Money> billAllocations) {
        validateAmountAndAllocations(amount, billAllocations);
        if (billAllocations != null) {
            for (Map.Entry<Long, Money> entry : billAllocations.entrySet()) {
                Money balance = purchaseBillBalance(entry.getKey());
                if (entry.getValue().paisa() > balance.paisa()) {
                    throw new IllegalArgumentException("Allocation exceeds that bill's outstanding balance of "
                            + balance.toDisplayString() + ".");
                }
            }
        }

        Payment payment = new Payment(0, Payment.Direction.OUT, Payment.PartyType.SUPPLIER, supplierId, paymentDate,
                amount, mode, referenceNo, note, false, null, null);
        long paymentId = paymentRepository.create(payment);
        if (billAllocations != null) {
            for (Map.Entry<Long, Money> entry : billAllocations.entrySet()) {
                paymentAllocationRepository.create(new PaymentAllocation(0, paymentId,
                        PaymentAllocation.TargetType.PURCHASE_BILL, entry.getKey(), entry.getValue()));
            }
        }
        auditLogService.record("PAYMENT_RECORDED", "PAYMENT", paymentId, null,
                Map.of("direction", "OUT", "supplierId", supplierId, "amount", amount.toDisplayString(),
                        "mode", mode.name()));
        return paymentId;
    }

    private static void validateAmountAndAllocations(Money amount, Map<Long, Money> allocations) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }
        if (allocations != null && !allocations.isEmpty()) {
            long allocatedPaisa = allocations.values().stream().mapToLong(Money::paisa).sum();
            if (allocatedPaisa > amount.paisa()) {
                throw new IllegalArgumentException("Allocated amounts cannot exceed the payment total.");
            }
        }
    }

    /** FR-SAL-11: called by {@code SalesInvoiceService.cancelInvoice} - removes the link
     *  between the cancelled invoice and any payments allocated to it. See
     *  {@link PaymentAllocationRepository#deleteByTarget} for why this is safe. */
    @Transactional
    public void reverseAllocationsForInvoice(long salesInvoiceId) {
        paymentAllocationRepository.deleteByTarget(PaymentAllocation.TargetType.SALES_INVOICE, salesInvoiceId);
    }

    // ---- Deletion (FR-PAY-06) ----------------------------------------------------------

    /** Soft delete only - the row, its reason and its timestamp stay, and every balance
     *  formula already excludes deleted payments, so the affected balance is restored the
     *  moment this commits with no separate bookkeeping needed. */
    @Transactional
    public void deletePayment(long paymentId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to delete a payment.");
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found."));
        if (payment.deleted()) {
            throw new IllegalStateException("This payment has already been deleted.");
        }
        paymentRepository.softDelete(paymentId, reason.trim(), LocalDateTime.now());
        auditLogService.record("PAYMENT_DELETED", "PAYMENT", paymentId, reason.trim());
    }

    // ---- Reads --------------------------------------------------------------------------

    public Optional<Payment> findById(long id) {
        return paymentRepository.findById(id);
    }

    public List<PaymentAllocation> allocationsFor(long paymentId) {
        return paymentAllocationRepository.findByPaymentId(paymentId);
    }

    public List<Payment> search(PaymentSearchCriteria criteria) {
        return paymentRepository.search(criteria);
    }
}
