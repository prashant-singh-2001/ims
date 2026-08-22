package com.piecetrack.service;

import com.piecetrack.domain.Customer;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Payment;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.domain.SalesLine;
import com.piecetrack.domain.ShopProfile;
import com.piecetrack.money.Money;
import com.piecetrack.repository.CustomerRepository;
import com.piecetrack.repository.ItemModelRepository;
import com.piecetrack.repository.SalesInvoiceListRow;
import com.piecetrack.repository.SalesInvoiceRepository;
import com.piecetrack.repository.SalesInvoiceSearchCriteria;
import com.piecetrack.repository.SalesLineRepository;
import com.piecetrack.repository.SequenceCounterRepository;
import com.piecetrack.repository.ShopProfileRepository;
import com.piecetrack.util.FinancialYear;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Billing (FR-SAL-01..09,12) and cancellation (FR-SAL-11). {@link #preview} is the one
 * source of truth for every tax/discount/rounding computation - both the billing screen's
 * live totals and {@link #createInvoice} call it, so what the owner sees before saving is
 * guaranteed to match what gets saved (the same pattern {@code PurchaseBillService} uses).
 * <p>
 * The invoice number is drawn last, immediately before the row is written, specifically
 * so that any validation failure - a piece no longer IN_STOCK, a bad discount, a missing
 * customer - happens before a number is ever consumed (FR-SAL-07: "allocated only on
 * save, never on screen entry that might be abandoned").
 */
@Service
public class SalesInvoiceService {

    public record InvoiceLineInput(long pieceId, Money unitPrice, Money discountAmount) {
    }

    public record LinePreview(long pieceId, Money unitPriceEntered, Money discountAmount, Money baseExclusive,
                               Money billDiscountShare, Money taxableValue, Money cgst, Money sgst, Money igst,
                               Money lineTotal) {
    }

    public record InvoicePreview(boolean interstate, List<LinePreview> lines, Money grossValue,
                                  Money lineDiscountTotal, Money taxableValue, Money cgstAmount,
                                  Money sgstAmount, Money igstAmount, Money roundOff, Money grandTotal) {
    }

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesLineRepository salesLineRepository;
    private final CustomerRepository customerRepository;
    private final ShopProfileRepository shopProfileRepository;
    private final ItemModelRepository itemModelRepository;
    private final PieceService pieceService;
    private final SequenceCounterRepository sequenceCounterRepository;
    private final PaymentService paymentService;
    private final AuditLogService auditLogService;
    private final SettingsService settingsService;

    public SalesInvoiceService(SalesInvoiceRepository salesInvoiceRepository, SalesLineRepository salesLineRepository,
                                CustomerRepository customerRepository, ShopProfileRepository shopProfileRepository,
                                ItemModelRepository itemModelRepository, PieceService pieceService,
                                SequenceCounterRepository sequenceCounterRepository, PaymentService paymentService,
                                AuditLogService auditLogService, SettingsService settingsService) {
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesLineRepository = salesLineRepository;
        this.customerRepository = customerRepository;
        this.shopProfileRepository = shopProfileRepository;
        this.itemModelRepository = itemModelRepository;
        this.pieceService = pieceService;
        this.sequenceCounterRepository = sequenceCounterRepository;
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
        this.settingsService = settingsService;
    }

    public Optional<SalesInvoice> findById(long id) {
        return salesInvoiceRepository.findById(id);
    }

    public List<SalesLine> linesFor(long invoiceId) {
        return salesLineRepository.findBySalesInvoiceId(invoiceId);
    }

    public List<SalesInvoiceListRow> search(SalesInvoiceSearchCriteria criteria) {
        return salesInvoiceRepository.search(criteria);
    }

    /** grand_total minus credit notes minus allocated payments (FR-PAY-03), via
     *  {@link PaymentService} - the single source of truth for every balance in the
     *  system. Zero for a cancelled invoice. */
    public Money balance(SalesInvoice invoice) {
        return paymentService.invoiceBalance(invoice.id());
    }

    /** Computes every derived figure without persisting or touching stock - safe to call
     *  repeatedly as the owner edits the bill. Pieces already sold, discounts that exceed
     *  a line's value, and a bill discount larger than the whole bill are all rejected
     *  here, before {@link #createInvoice} would otherwise draw an invoice number for
     *  nothing.
     *  <p>
     *  M10: when {@link SettingsService#isGstEnabled()} is off, every line's tax is forced
     *  to zero and {@code interstate} is forced to {@code false} below - deliberately without
     *  ever evaluating {@code placeOfSupplyStateCode}, via {@code &&} short-circuiting, so a
     *  null place-of-supply (that field is hidden from the billing screen when GST is off)
     *  can never NPE here. */
    public InvoicePreview preview(String placeOfSupplyStateCode, List<InvoiceLineInput> lineInputs,
                                   boolean priceInclusive, Money billDiscount) {
        if (lineInputs == null || lineInputs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item to the bill.");
        }
        ShopProfile shop = shopProfileRepository.find()
                .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
        boolean gstEnabled = settingsService.isGstEnabled();
        boolean interstate = gstEnabled && !placeOfSupplyStateCode.equals(shop.stateCode());
        Money discount = billDiscount == null ? Money.ZERO : billDiscount;

        record RawLine(InvoiceLineInput input, Piece piece, ItemModel model, Money baseExclusive,
                        Money afterLineDiscount) {
        }

        List<RawLine> raw = new ArrayList<>();
        for (InvoiceLineInput input : lineInputs) {
            Piece piece = pieceService.findById(input.pieceId())
                    .orElseThrow(() -> new IllegalArgumentException("Piece not found: " + input.pieceId()));
            if (piece.state() != Piece.State.IN_STOCK) {
                throw new IllegalStateException(
                        "Piece " + piece.tag() + " is " + piece.state() + " and cannot be sold.");
            }
            ItemModel model = itemModelRepository.findById(piece.itemModelId())
                    .orElseThrow(() -> new IllegalStateException("Item model not found."));
            Money unitPrice = input.unitPrice();
            if (unitPrice == null || unitPrice.isNegative()) {
                throw new IllegalArgumentException("Price for " + piece.tag() + " must be zero or more.");
            }
            Money lineDiscount = input.discountAmount() == null ? Money.ZERO : input.discountAmount();

            Money baseExclusive;
            if (gstEnabled && priceInclusive) {
                BigDecimal divisor = BigDecimal.ONE.add(
                        model.gstRate().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_EVEN));
                baseExclusive = Money.ofRupees(unitPrice.rupees().divide(divisor, 2, RoundingMode.HALF_EVEN));
            } else {
                baseExclusive = unitPrice;
            }
            Money afterLineDiscount = baseExclusive.minus(lineDiscount);
            if (afterLineDiscount.isNegative()) {
                throw new IllegalArgumentException("Discount for " + piece.tag() + " cannot exceed its price.");
            }
            raw.add(new RawLine(input, piece, model, baseExclusive, afterLineDiscount));
        }

        long distinctPieces = raw.stream().map(r -> r.piece().id()).distinct().count();
        if (distinctPieces != raw.size()) {
            throw new IllegalArgumentException("The same piece cannot be added to the bill twice.");
        }

        Money totalAfterLineDiscount = Money.ZERO;
        for (RawLine r : raw) {
            totalAfterLineDiscount = totalAfterLineDiscount.plus(r.afterLineDiscount());
        }
        if (discount.paisa() > totalAfterLineDiscount.paisa()) {
            throw new IllegalArgumentException("Bill discount cannot exceed the items' total value.");
        }

        BigDecimal[] weights = raw.stream().map(r -> r.afterLineDiscount().rupees()).toArray(BigDecimal[]::new);
        Money[] billDiscountShares = discount.apportion(weights);

        List<LinePreview> lines = new ArrayList<>();
        Money grossValue = Money.ZERO;
        Money lineDiscountTotal = Money.ZERO;
        Money taxableTotal = Money.ZERO;
        Money cgstTotal = Money.ZERO;
        Money sgstTotal = Money.ZERO;
        Money igstTotal = Money.ZERO;

        for (int i = 0; i < raw.size(); i++) {
            RawLine r = raw.get(i);
            Money finalTaxable = r.afterLineDiscount().minus(billDiscountShares[i]);
            Money tax = gstEnabled
                    ? Money.ofRupees(finalTaxable.rupees()
                            .multiply(r.model().gstRate())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                    : Money.ZERO;

            long taxPaisa = tax.paisa();
            long cgstPaisa = interstate ? 0 : taxPaisa / 2;
            long sgstPaisa = interstate ? 0 : taxPaisa - cgstPaisa;
            long igstPaisa = interstate ? taxPaisa : 0;
            Money cgst = Money.ofPaisa(cgstPaisa);
            Money sgst = Money.ofPaisa(sgstPaisa);
            Money igst = Money.ofPaisa(igstPaisa);
            Money lineTotal = finalTaxable.plus(cgst).plus(sgst).plus(igst);

            Money lineDiscountInput = r.input().discountAmount() == null ? Money.ZERO : r.input().discountAmount();

            lines.add(new LinePreview(r.piece().id(), r.input().unitPrice(), lineDiscountInput, r.baseExclusive(),
                    billDiscountShares[i], finalTaxable, cgst, sgst, igst, lineTotal));

            grossValue = grossValue.plus(r.baseExclusive());
            lineDiscountTotal = lineDiscountTotal.plus(lineDiscountInput);
            taxableTotal = taxableTotal.plus(finalTaxable);
            cgstTotal = cgstTotal.plus(cgst);
            sgstTotal = sgstTotal.plus(sgst);
            igstTotal = igstTotal.plus(igst);
        }

        Money beforeRounding = taxableTotal.plus(cgstTotal).plus(sgstTotal).plus(igstTotal);
        Money grandTotal = Money.ofRupees(beforeRounding.rupees().setScale(0, RoundingMode.HALF_UP));
        Money roundOff = grandTotal.minus(beforeRounding);

        return new InvoicePreview(interstate, lines, grossValue, lineDiscountTotal, taxableTotal, cgstTotal,
                sgstTotal, igstTotal, roundOff, grandTotal);
    }

    /** Delegates to the full overload with no advance payment. */
    @Transactional
    public long createInvoice(long customerId, String placeOfSupplyStateCode, boolean priceInclusive,
                               List<InvoiceLineInput> lineInputs, Money billDiscount, LocalDate invoiceDate) {
        return createInvoice(customerId, placeOfSupplyStateCode, priceInclusive, lineInputs, billDiscount,
                invoiceDate, null, null, null, null);
    }

    /** FR-SAL-08: allocates the invoice number, writes the invoice and its lines, copies
     *  each piece's landed cost into {@code cost_at_sale}, and moves every piece to SOLD -
     *  all in the one transaction this method runs in. Any failure rolls back all of it.
     *  <p>
     *  FR-PAY-01: an optional advance collected at the moment of billing - pass
     *  {@code advanceAmount == null} (or zero) to skip it. When present it is recorded as a
     *  customer payment allocated in full to the invoice just created, via
     *  {@link PaymentService#recordCustomerPayment}. */
    @Transactional
    public long createInvoice(long customerId, String placeOfSupplyStateCode, boolean priceInclusive,
                               List<InvoiceLineInput> lineInputs, Money billDiscount, LocalDate invoiceDate,
                               Money advanceAmount, Payment.Mode advanceMode, String advanceReferenceNo,
                               String advanceNote) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found."));
        InvoicePreview preview = preview(placeOfSupplyStateCode, lineInputs, priceInclusive, billDiscount);

        // M10: sales_invoice.place_of_supply_state_code is NOT NULL with no default. The
        // billing screen hides this field when GST is off, so a null/blank value here is
        // filled with the shop's own state code rather than rejected - preview() above
        // never touches this value anyway once GST is off (see its own note).
        boolean gstEnabled = settingsService.isGstEnabled();
        String resolvedPlaceOfSupplyStateCode = placeOfSupplyStateCode;
        if (!gstEnabled && (resolvedPlaceOfSupplyStateCode == null || resolvedPlaceOfSupplyStateCode.isBlank())) {
            resolvedPlaceOfSupplyStateCode = shopProfileRepository.find()
                    .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."))
                    .stateCode();
        }

        String fy = FinancialYear.of(invoiceDate);
        long seq = sequenceCounterRepository.next("INVOICE", fy);
        String invoiceNo = "INV/" + fy + "/" + String.format("%04d", seq);

        SalesInvoice toSave = new SalesInvoice(0, invoiceNo, fy, invoiceDate, customer.id(),
                resolvedPlaceOfSupplyStateCode,
                preview.interstate(), priceInclusive, preview.grossValue(), preview.lineDiscountTotal(),
                billDiscount == null ? Money.ZERO : billDiscount, preview.taxableValue(), preview.cgstAmount(),
                preview.sgstAmount(), preview.igstAmount(), preview.roundOff(), preview.grandTotal(),
                SalesInvoice.Status.ACTIVE, null, null, null, null);
        long invoiceId = salesInvoiceRepository.create(toSave);

        for (LinePreview line : preview.lines()) {
            Piece piece = pieceService.findById(line.pieceId())
                    .orElseThrow(() -> new IllegalStateException("Piece disappeared mid-save: " + line.pieceId()));
            ItemModel model = itemModelRepository.findById(piece.itemModelId())
                    .orElseThrow(() -> new IllegalStateException("Item model not found."));

            // M10: sales_line.hsn_snapshot/gst_rate mirror the sentinel a GST-off item model
            // is itself saved with (ItemModelService.applyGstSentinelIfDisabled) - a model
            // that was created back when GST was on still has its real historical HSN/rate
            // stored, but a sale made while the toggle is off must not let that leak onto a
            // line that otherwise carries zero tax throughout.
            salesLineRepository.create(new SalesLine(0, invoiceId, piece.id(), model.id(), model.modelName(),
                    gstEnabled ? model.hsnCode() : "", gstEnabled ? model.gstRate() : BigDecimal.ZERO,
                    line.unitPriceEntered(), line.discountAmount(),
                    line.taxableValue(), line.cgst(), line.sgst(), line.igst(), line.lineTotal(),
                    piece.landedCost(), null));

            pieceService.markSold(piece.id(), invoiceId);

            // FR-SYS-03: "cost or price overridden" - the billing screen pre-fills from the
            // model's default selling price (FR-SAL-03) and always allows changing it; log
            // only the cases where the owner actually did, not every line of every sale.
            if (model.defaultSalePrice() != null && !model.defaultSalePrice().equals(line.unitPriceEntered())) {
                auditLogService.record("PRICE_OVERRIDDEN", "PIECE", piece.id(),
                        Map.of("defaultSalePrice", model.defaultSalePrice().toDisplayString()),
                        Map.of("enteredPrice", line.unitPriceEntered().toDisplayString(), "invoiceId", invoiceId));
            }
        }

        if (advanceAmount != null && advanceAmount.isPositive()) {
            paymentService.recordCustomerPayment(customer.id(), advanceAmount, advanceMode, advanceReferenceNo,
                    advanceNote, invoiceDate, Map.of(invoiceId, advanceAmount));
        }

        auditLogService.record("INVOICE_CREATED", "SALES_INVOICE", invoiceId, null,
                Map.of("invoiceNo", invoiceNo, "customerId", customer.id(),
                        "grandTotal", preview.grandTotal().toDisplayString()));

        return invoiceId;
    }

    /** FR-SAL-11: restores every sold piece to IN_STOCK, reverses any payments allocated to
     *  it via {@link PaymentService#reverseAllocationsForInvoice}, and marks the invoice
     *  cancelled - never deleted, and its number never reused. */
    @Transactional
    public void cancelInvoice(long invoiceId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to cancel an invoice.");
        }
        SalesInvoice invoice = salesInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
        if (invoice.status() != SalesInvoice.Status.ACTIVE) {
            throw new IllegalStateException("Only an active invoice can be cancelled.");
        }
        for (SalesLine line : salesLineRepository.findBySalesInvoiceId(invoiceId)) {
            pieceService.markInvoiceCancelled(line.pieceId(), invoiceId);
        }
        paymentService.reverseAllocationsForInvoice(invoiceId);
        salesInvoiceRepository.cancel(invoiceId, LocalDateTime.now(), reason.trim());
        auditLogService.record("INVOICE_CANCELLED", "SALES_INVOICE", invoiceId,
                Map.of("status", invoice.status().name()),
                Map.of("status", SalesInvoice.Status.CANCELLED.name(), "reason", reason.trim()));
    }
}
