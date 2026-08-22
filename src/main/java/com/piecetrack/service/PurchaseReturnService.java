package com.piecetrack.service;

import com.piecetrack.domain.Piece;
import com.piecetrack.domain.PurchaseBill;
import com.piecetrack.domain.PurchaseLine;
import com.piecetrack.domain.PurchaseReturn;
import com.piecetrack.domain.PurchaseReturnLine;
import com.piecetrack.money.Money;
import com.piecetrack.repository.PurchaseLineRepository;
import com.piecetrack.repository.PurchaseReturnLineRepository;
import com.piecetrack.repository.PurchaseReturnRepository;
import com.piecetrack.repository.SequenceCounterRepository;
import com.piecetrack.util.FinancialYear;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Purchase returns (FR-PUR-07): sends specific {@code IN_STOCK} pieces back to their
 * supplier, with a debit note that credits back exactly the taxable value and tax those
 * pieces originally carried.
 * <p>
 * A piece's own {@code landed_cost} includes its share of bill-level freight/charges,
 * which is normally not reversed on return (the delivery already happened), so a debit
 * note recomputes each returned piece's share straight from its originating purchase
 * line's taxable value and tax - apportioned evenly across every piece that line ever
 * produced, using the same {@link Money#apportion} pattern as everywhere else in this
 * system, so a partial return of a multi-unit line still adds up exactly.
 */
@Service
public class PurchaseReturnService {

    private final PurchaseReturnRepository purchaseReturnRepository;
    private final PurchaseReturnLineRepository purchaseReturnLineRepository;
    private final PurchaseLineRepository purchaseLineRepository;
    private final PurchaseBillService purchaseBillService;
    private final PieceService pieceService;
    private final SequenceCounterRepository sequenceCounterRepository;

    public PurchaseReturnService(PurchaseReturnRepository purchaseReturnRepository,
                                  PurchaseReturnLineRepository purchaseReturnLineRepository,
                                  PurchaseLineRepository purchaseLineRepository,
                                  PurchaseBillService purchaseBillService, PieceService pieceService,
                                  SequenceCounterRepository sequenceCounterRepository) {
        this.purchaseReturnRepository = purchaseReturnRepository;
        this.purchaseReturnLineRepository = purchaseReturnLineRepository;
        this.purchaseLineRepository = purchaseLineRepository;
        this.purchaseBillService = purchaseBillService;
        this.pieceService = pieceService;
        this.sequenceCounterRepository = sequenceCounterRepository;
    }

    public List<PurchaseReturn> returnsFor(long purchaseBillId) {
        // Delegated to the repository directly - a read with no business logic attached.
        return purchaseReturnRepository.findByPurchaseBillId(purchaseBillId);
    }

    @Transactional
    public long createReturn(long purchaseBillId, List<Long> pieceIds, String reason, LocalDate returnDate) {
        if (pieceIds == null || pieceIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one piece to return.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required.");
        }
        PurchaseBill bill = purchaseBillService.findById(purchaseBillId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase bill not found."));
        if (bill.status() != PurchaseBill.Status.RECEIVED) {
            throw new IllegalStateException("Only a received bill has pieces that can be returned.");
        }

        Map<Long, List<Piece>> returnedByLine = new LinkedHashMap<>();
        for (Long pieceId : pieceIds) {
            Piece piece = pieceService.findById(pieceId)
                    .orElseThrow(() -> new IllegalArgumentException("Piece not found: " + pieceId));
            if (piece.purchaseLineId() == null) {
                throw new IllegalArgumentException("Piece " + piece.tag() + " was not received on a purchase bill.");
            }
            // Checked here, not left to the database's UNIQUE constraint on
            // purchase_return_line.piece_id or to markReturnedToSupplier's own state
            // check (both fire too late - after other lines in this same return may
            // already have been written) - a piece that isn't IN_STOCK, including one
            // already returned, must fail fast with a clean message (NFR-11).
            if (piece.state() != Piece.State.IN_STOCK) {
                throw new IllegalStateException(
                        "Piece " + piece.tag() + " is " + piece.state() + " and cannot be returned to the supplier.");
            }
            returnedByLine.computeIfAbsent(piece.purchaseLineId(), k -> new ArrayList<>()).add(piece);
        }

        Money totalTaxable = Money.ZERO;
        Money totalCgst = Money.ZERO;
        Money totalSgst = Money.ZERO;
        Money totalIgst = Money.ZERO;
        List<PurchaseReturnLine> lineDrafts = new ArrayList<>();

        for (Map.Entry<Long, List<Piece>> entry : returnedByLine.entrySet()) {
            PurchaseLine purchaseLine = purchaseLineRepository.findById(entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("Purchase line not found: " + entry.getKey()));
            List<Piece> allPiecesOnLine = pieceService.findByPurchaseLineId(purchaseLine.id());

            BigDecimal[] equalShares = new BigDecimal[allPiecesOnLine.size()];
            Arrays.fill(equalShares, BigDecimal.ONE);
            Money[] taxableShares = purchaseLine.taxableValue().apportion(equalShares);
            Money[] cgstShares = purchaseLine.cgstAmount().apportion(equalShares);
            Money[] sgstShares = purchaseLine.sgstAmount().apportion(equalShares);
            Money[] igstShares = purchaseLine.igstAmount().apportion(equalShares);

            for (Piece returnedPiece : entry.getValue()) {
                int index = indexOfPieceId(allPiecesOnLine, returnedPiece.id());
                Money taxable = taxableShares[index];
                Money cgst = cgstShares[index];
                Money sgst = sgstShares[index];
                Money igst = igstShares[index];
                Money lineTotal = taxable.plus(cgst).plus(sgst).plus(igst);

                totalTaxable = totalTaxable.plus(taxable);
                totalCgst = totalCgst.plus(cgst);
                totalSgst = totalSgst.plus(sgst);
                totalIgst = totalIgst.plus(igst);

                lineDrafts.add(new PurchaseReturnLine(0, 0, returnedPiece.id(), taxable, cgst, sgst, igst, lineTotal));
            }
        }

        Money totalAmount = totalTaxable.plus(totalCgst).plus(totalSgst).plus(totalIgst);
        String debitNoteNo = generateDebitNoteNo();

        PurchaseReturn toSave = new PurchaseReturn(0, purchaseBillId, debitNoteNo, returnDate, reason.trim(),
                totalTaxable, totalCgst, totalSgst, totalIgst, totalAmount, null);
        long returnId = purchaseReturnRepository.create(toSave);

        for (PurchaseReturnLine draft : lineDrafts) {
            purchaseReturnLineRepository.create(new PurchaseReturnLine(0, returnId, draft.pieceId(),
                    draft.taxableValue(), draft.cgstAmount(), draft.sgstAmount(), draft.igstAmount(),
                    draft.lineTotal()));
            pieceService.markReturnedToSupplier(draft.pieceId(), returnId);
        }

        return returnId;
    }

    private static int indexOfPieceId(List<Piece> pieces, long pieceId) {
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).id() == pieceId) {
                return i;
            }
        }
        throw new IllegalStateException("Piece " + pieceId + " not found among its own purchase line's pieces.");
    }

    private String generateDebitNoteNo() {
        String fy = FinancialYear.current();
        long seq = sequenceCounterRepository.next("DEBIT_NOTE", fy);
        return "DN/" + fy + "/" + String.format("%04d", seq);
    }
}
