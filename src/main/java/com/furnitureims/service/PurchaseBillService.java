package com.furnitureims.service;

import com.furnitureims.domain.PurchaseBill;
import com.furnitureims.domain.PurchaseLine;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.PurchaseBillListRow;
import com.furnitureims.repository.PurchaseBillRepository;
import com.furnitureims.repository.PurchaseBillSearchCriteria;
import com.furnitureims.repository.PurchaseLineRepository;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Purchase bill lifecycle: draft, confirm receipt (FR-PUR-03..06), reverse (FR-PUR-08).
 * <p>
 * A DRAFT bill has no stock effect at all - {@link #saveDraft} only ever writes the bill
 * and line rows. Confirming receipt is the one moment pieces get created, with the exact
 * landed-cost apportionment from docs/02-data-model.md section 4.4, reusing
 * {@link Money#apportion} rather than re-deriving that arithmetic here.
 */
@Service
public class PurchaseBillService {

    /** One line as entered on the bill entry screen, before tax/apportionment
     *  calculation - the service computes everything derived from these five inputs. */
    public record LineInput(long itemModelId, int quantity, Money rate, Money discountAmount, BigDecimal gstRate) {
    }

    /** Read-only totals for the bill entry screen's live preview - the same numbers
     *  {@link #saveDraft} persists, computed the same way, so what the owner sees before
     *  saving is guaranteed to match what gets saved. */
    public record PreviewTotals(boolean interstate, Money taxableValue, Money cgstAmount, Money sgstAmount,
                                 Money igstAmount, Money charges, Money roundOff, Money grandTotal) {
    }

    private final PurchaseBillRepository purchaseBillRepository;
    private final PurchaseLineRepository purchaseLineRepository;
    private final SupplierRepository supplierRepository;
    private final ShopProfileRepository shopProfileRepository;
    private final PieceService pieceService;
    private final PaymentService paymentService;
    private final SettingsService settingsService;

    public PurchaseBillService(PurchaseBillRepository purchaseBillRepository,
                                PurchaseLineRepository purchaseLineRepository,
                                SupplierRepository supplierRepository,
                                ShopProfileRepository shopProfileRepository,
                                PieceService pieceService, PaymentService paymentService,
                                SettingsService settingsService) {
        this.purchaseBillRepository = purchaseBillRepository;
        this.purchaseLineRepository = purchaseLineRepository;
        this.supplierRepository = supplierRepository;
        this.shopProfileRepository = shopProfileRepository;
        this.pieceService = pieceService;
        this.paymentService = paymentService;
        this.settingsService = settingsService;
    }

    public Optional<PurchaseBill> findById(long id) {
        return purchaseBillRepository.findById(id);
    }

    public List<PurchaseLine> linesFor(long billId) {
        return purchaseLineRepository.findByPurchaseBillId(billId);
    }

    public List<PurchaseBillListRow> search(PurchaseBillSearchCriteria criteria) {
        return purchaseBillRepository.search(criteria);
    }

    /** grand_total minus debit notes minus allocated payments (FR-PAY-03), via
     *  {@link PaymentService} - the single source of truth for every balance in the
     *  system. Zero unless the bill is RECEIVED. */
    public Money balance(PurchaseBill bill) {
        return paymentService.purchaseBillBalance(bill.id());
    }

    /** Computes totals without persisting anything, for the bill entry screen's live
     *  preview before the owner commits to Save/Confirm.
     *  <p>
     *  M10: when {@link SettingsService#isGstEnabled()} is off, {@code interstate} is forced
     *  to {@code false} without evaluating {@code supplier.stateCode()} (short-circuiting via
     *  {@code &&}, mirroring {@code SalesInvoiceService.preview}), and every line's tax is
     *  forced to zero in {@link #computeLines}. */
    public PreviewTotals preview(long supplierId, List<LineInput> lineInputs, Money freight,
                                  Money loadingCharges, Money otherCharges) {
        if (lineInputs == null || lineInputs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one line item.");
        }
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found."));
        ShopProfile shop = shopProfileRepository.find()
                .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
        boolean gstEnabled = settingsService.isGstEnabled();
        boolean interstate = gstEnabled && !supplier.stateCode().equals(shop.stateCode());
        List<ComputedLine> computed = computeLines(lineInputs, interstate, gstEnabled);

        Money taxableTotal = Money.ZERO;
        Money cgstTotal = Money.ZERO;
        Money sgstTotal = Money.ZERO;
        Money igstTotal = Money.ZERO;
        for (ComputedLine line : computed) {
            taxableTotal = taxableTotal.plus(line.taxableValue);
            cgstTotal = cgstTotal.plus(line.cgst);
            sgstTotal = sgstTotal.plus(line.sgst);
            igstTotal = igstTotal.plus(line.igst);
        }

        Money charges = (freight == null ? Money.ZERO : freight)
                .plus(loadingCharges == null ? Money.ZERO : loadingCharges)
                .plus(otherCharges == null ? Money.ZERO : otherCharges);
        Money beforeRounding = taxableTotal.plus(cgstTotal).plus(sgstTotal).plus(igstTotal).plus(charges);
        Money grandTotal = Money.ofRupees(beforeRounding.rupees().setScale(0, RoundingMode.HALF_UP));
        Money roundOff = grandTotal.minus(beforeRounding);

        return new PreviewTotals(interstate, taxableTotal, cgstTotal, sgstTotal, igstTotal, charges,
                roundOff, grandTotal);
    }

    /** @param existingBillId null to create a new draft, or an existing DRAFT bill's id to
     *                        replace its header and lines wholesale.
     *  @return the bill's id. */
    @Transactional
    public long saveDraft(Long existingBillId, long supplierId, String supplierBillNo, LocalDate billDate,
                           LocalDate receivedDate, Money freight, Money loadingCharges, Money otherCharges,
                           String notes, List<LineInput> lineInputs) {
        PreviewTotals totals = preview(supplierId, lineInputs, freight, loadingCharges, otherCharges);
        List<ComputedLine> computed = computeLines(lineInputs, totals.interstate(), settingsService.isGstEnabled());

        PurchaseBill toSave = new PurchaseBill(existingBillId == null ? 0 : existingBillId, supplierId,
                requireText(supplierBillNo, "Supplier bill number"), billDate, receivedDate, totals.interstate(),
                totals.taxableValue(), freight == null ? Money.ZERO : freight,
                loadingCharges == null ? Money.ZERO : loadingCharges, otherCharges == null ? Money.ZERO : otherCharges,
                totals.cgstAmount(), totals.sgstAmount(), totals.igstAmount(), totals.roundOff(),
                totals.grandTotal(), PurchaseBill.Status.DRAFT, notes);

        long billId;
        if (existingBillId == null) {
            if (purchaseBillRepository.existsBySupplierAndBillNo(supplierId, toSave.supplierBillNo())) {
                throw new IllegalArgumentException(
                        "Bill number \"" + toSave.supplierBillNo() + "\" already exists for this supplier.");
            }
            billId = purchaseBillRepository.create(toSave);
        } else {
            PurchaseBill existing = purchaseBillRepository.findById(existingBillId)
                    .orElseThrow(() -> new IllegalArgumentException("Purchase bill not found."));
            if (existing.status() != PurchaseBill.Status.DRAFT) {
                throw new IllegalStateException("Only a draft bill can be edited.");
            }
            billId = existingBillId;
            purchaseBillRepository.update(toSave);
            purchaseLineRepository.deleteByPurchaseBillId(billId);
        }

        for (ComputedLine line : computed) {
            purchaseLineRepository.create(new PurchaseLine(0, billId, line.input.itemModelId(),
                    line.input.quantity(), line.input.rate(), line.discountAmount, line.taxableValue,
                    line.input.gstRate(), line.cgst, line.sgst, line.igst, line.lineTotal));
        }
        return billId;
    }

    /** FR-PUR-05/06: creates one piece per unit of quantity on every line, each costed by
     *  apportioning the line's taxable value plus its share of bill-level charges evenly
     *  across that line's pieces - see docs/02-data-model.md section 4.4 for the formula
     *  this reproduces using {@link Money#apportion} twice (once across lines by taxable
     *  value, once across a line's own pieces in equal shares). */
    @Transactional
    public void confirmReceipt(long billId) {
        PurchaseBill bill = purchaseBillRepository.findById(billId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase bill not found."));
        if (bill.status() != PurchaseBill.Status.DRAFT) {
            throw new IllegalStateException("Only a draft bill can be confirmed for receipt.");
        }
        List<PurchaseLine> lines = purchaseLineRepository.findByPurchaseBillId(billId);
        if (lines.isEmpty()) {
            throw new IllegalStateException("This bill has no lines to receive.");
        }

        BigDecimal[] lineWeights = lines.stream().map(l -> l.taxableValue().rupees()).toArray(BigDecimal[]::new);
        Money[] lineChargeShares = bill.chargePool().apportion(lineWeights);

        for (int i = 0; i < lines.size(); i++) {
            PurchaseLine line = lines.get(i);
            Money lineTotalCost = line.taxableValue().plus(lineChargeShares[i]);

            BigDecimal[] equalShares = new BigDecimal[line.quantity()];
            java.util.Arrays.fill(equalShares, BigDecimal.ONE);
            Money[] perPieceCosts = lineTotalCost.apportion(equalShares);

            pieceService.createFromPurchase(line.itemModelId(), line.id(), List.of(perPieceCosts), bill.receivedDate());
        }

        purchaseBillRepository.updateStatus(billId, PurchaseBill.Status.RECEIVED);
    }

    /** FR-PUR-08: only permitted while no piece from this bill has left IN_STOCK, and
     *  undoes exactly the piece-creation {@link #confirmReceipt} did, putting the bill
     *  back into an editable DRAFT. */
    @Transactional
    public void reverseReceipt(long billId) {
        PurchaseBill bill = purchaseBillRepository.findById(billId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase bill not found."));
        if (bill.status() != PurchaseBill.Status.RECEIVED) {
            throw new IllegalStateException("Only a received bill can be reversed.");
        }
        List<String> blocking = purchaseBillRepository.tagsOfSoldOrMovedPieces(billId);
        if (!blocking.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot reverse - these pieces are no longer in stock: " + String.join(", ", blocking));
        }
        for (PurchaseLine line : purchaseLineRepository.findByPurchaseBillId(billId)) {
            pieceService.deletePiecesCreatedByPurchaseLine(line.id());
        }
        purchaseBillRepository.updateStatus(billId, PurchaseBill.Status.DRAFT);
    }

    private record ComputedLine(LineInput input, Money discountAmount, Money taxableValue,
                                 Money cgst, Money sgst, Money igst, Money lineTotal) {
    }

    private static List<ComputedLine> computeLines(List<LineInput> inputs, boolean interstate, boolean gstEnabled) {
        List<ComputedLine> result = new ArrayList<>();
        for (LineInput input : inputs) {
            if (input.quantity() < 1) {
                throw new IllegalArgumentException("Quantity must be at least 1.");
            }
            Money discount = input.discountAmount() == null ? Money.ZERO : input.discountAmount();
            Money gross = Money.ofPaisa(input.rate().paisa() * input.quantity());
            Money taxableValue = gross.minus(discount);
            if (taxableValue.isNegative()) {
                throw new IllegalArgumentException("Discount cannot exceed the line's value.");
            }

            Money tax = gstEnabled
                    ? Money.ofRupees(taxableValue.rupees()
                            .multiply(input.gstRate())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                    : Money.ZERO;

            long taxPaisa = tax.paisa();
            long cgstPaisa = interstate ? 0 : taxPaisa / 2;
            long sgstPaisa = interstate ? 0 : taxPaisa - cgstPaisa;
            long igstPaisa = interstate ? taxPaisa : 0;

            Money cgst = Money.ofPaisa(cgstPaisa);
            Money sgst = Money.ofPaisa(sgstPaisa);
            Money igst = Money.ofPaisa(igstPaisa);
            Money lineTotal = taxableValue.plus(cgst).plus(sgst).plus(igst);

            result.add(new ComputedLine(input, discount, taxableValue, cgst, sgst, igst, lineTotal));
        }
        return result;
    }

    private static String requireText(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return text.trim();
    }
}
