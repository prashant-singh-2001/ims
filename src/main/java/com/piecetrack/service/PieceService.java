package com.piecetrack.service;

import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.StockMovement;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ItemModelRepository;
import com.piecetrack.repository.PieceRepository;
import com.piecetrack.repository.PieceSearchCriteria;
import com.piecetrack.repository.PieceSummary;
import com.piecetrack.repository.StockMovementRepository;
import com.piecetrack.repository.SequenceCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the piece register: creation, the state machine (FR-PIECE-04) and its audit trail.
 * <p>
 * {@link #changeState} only drives the four transitions an owner triggers manually from
 * the piece register screen - IN_STOCK to/from DAMAGED, and either to WRITTEN_OFF. The
 * remaining transitions in docs/02-data-model.md's diagram (IN_STOCK to SOLD,
 * IN_STOCK to RETURNED_TO_SUPPLIER, SOLD back to IN_STOCK) happen through the sales and
 * purchase modules instead - those need a ref_type/ref_id pointing at the invoice or bill
 * that caused them, which a generic "change state" call from this screen has no way to
 * supply correctly. {@link #createFromPurchase} and the purchase-return transition used by
 * {@code PurchaseReturnService} are the M3 half of that; sales is M4.
 */
@Service
public class PieceService {

    private static final Map<Piece.State, Set<Piece.State>> MANUAL_TRANSITIONS = Map.of(
            Piece.State.IN_STOCK, Set.of(Piece.State.DAMAGED, Piece.State.WRITTEN_OFF),
            Piece.State.DAMAGED, Set.of(Piece.State.IN_STOCK, Piece.State.WRITTEN_OFF)
    );

    private final PieceRepository pieceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SequenceCounterRepository sequenceCounterRepository;
    private final ItemModelRepository itemModelRepository;
    private final AuditLogService auditLogService;
    private final PiecePhotoService piecePhotoService;

    public PieceService(PieceRepository pieceRepository, StockMovementRepository stockMovementRepository,
                         SequenceCounterRepository sequenceCounterRepository,
                         ItemModelRepository itemModelRepository, AuditLogService auditLogService,
                         PiecePhotoService piecePhotoService) {
        this.pieceRepository = pieceRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.sequenceCounterRepository = sequenceCounterRepository;
        this.itemModelRepository = itemModelRepository;
        this.auditLogService = auditLogService;
        this.piecePhotoService = piecePhotoService;
    }

    /** @param deleteCount pieces with no retained document reference - permanently deletable
     *  @param writeOffCount pieces referenced by a retained cancelled-invoice, sales-return
     *                       or purchase-return line - must be written off instead, never
     *                       deleted (see {@link PieceRepository#hasRetainedDocumentReference}) */
    public record ZeroStockPreview(int deleteCount, int writeOffCount) {
        public int totalCount() {
            return deleteCount + writeOffCount;
        }
    }

    public record ZeroStockOutcome(int deletedCount, int writtenOffCount) {
    }

    public Optional<Piece> findById(long id) {
        return pieceRepository.findById(id);
    }

    /** Backs the billing screen's direct-tag entry (FR-SAL-01). */
    public Optional<Piece> findByTag(String tag) {
        return pieceRepository.findByTag(tag);
    }

    public List<PieceSummary> search(PieceSearchCriteria criteria) {
        return pieceRepository.search(criteria);
    }

    public List<StockMovement> history(long pieceId) {
        return stockMovementRepository.findByPieceId(pieceId);
    }

    /** All pieces created from one purchase line, in creation order - used by
     *  {@code PurchaseReturnService} to apportion that line's taxable value/tax across
     *  its pieces the same way {@link #createFromPurchase} apportioned their cost. */
    public List<Piece> findByPurchaseLineId(long purchaseLineId) {
        return pieceRepository.findByPurchaseLineId(purchaseLineId);
    }

    /** FR-PIECE-09: creates {@code quantity} individually tagged, individually costed
     *  pieces with no supplier bill behind them. */
    @Transactional
    public List<Piece> createOpeningStock(long itemModelId, int quantity, Money unitCost, Long locationId,
                                           LocalDate acquiredOn) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1.");
        }
        if (unitCost == null || unitCost.isNegative()) {
            throw new IllegalArgumentException("Per-piece cost is required and cannot be negative.");
        }
        ItemModel model = itemModelRepository.findById(itemModelId)
                .orElseThrow(() -> new IllegalArgumentException("Item model not found."));

        List<Piece> created = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            created.add(createPiece(itemModelId, model.modelCode(), Piece.SourceType.OPENING_STOCK, null,
                    unitCost, locationId, acquiredOn, StockMovement.Type.OPENING, "OPENING_STOCK", null,
                    "Opening stock entry"));
        }
        return created;
    }

    /** FR-PUR-05: one piece per unit of quantity on a purchase line, each with the
     *  landed cost {@code PurchaseBillService} apportioned to it (FR-PUR-06) - a different
     *  cost per piece, unlike opening stock's single uniform cost. No location is set;
     *  the owner assigns one later from the piece register (FR-PIECE-06). */
    @Transactional
    public List<Piece> createFromPurchase(long itemModelId, long purchaseLineId, List<Money> perPieceCosts,
                                           LocalDate acquiredOn) {
        ItemModel model = itemModelRepository.findById(itemModelId)
                .orElseThrow(() -> new IllegalArgumentException("Item model not found."));

        List<Piece> created = new ArrayList<>();
        for (Money cost : perPieceCosts) {
            created.add(createPiece(itemModelId, model.modelCode(), Piece.SourceType.PURCHASE, purchaseLineId,
                    cost, null, acquiredOn, StockMovement.Type.RECEIPT, "PURCHASE_LINE", purchaseLineId,
                    "Received on purchase bill"));
        }
        return created;
    }

    private Piece createPiece(long itemModelId, String modelCode, Piece.SourceType sourceType, Long purchaseLineId,
                               Money cost, Long locationId, LocalDate acquiredOn, StockMovement.Type movementType,
                               String refType, Long refId, String note) {
        String tag = generateTag(modelCode);
        Piece toInsert = new Piece(0, tag, itemModelId, sourceType, purchaseLineId, cost, locationId,
                Piece.State.IN_STOCK, null, acquiredOn, null, null);
        long id = pieceRepository.create(toInsert);
        stockMovementRepository.record(id, movementType, null, Piece.State.IN_STOCK, null, locationId,
                refType, refId, note);
        return pieceRepository.findById(id).orElseThrow();
    }

    /** FR-PUR-08: undoes the piece-creation half of confirming a receipt that is itself
     *  being reversed. Deliberately a hard delete - the one intentional exception to
     *  "nothing is hard-deleted" in this system - because the caller (PurchaseBillService)
     *  has already verified every such piece is still IN_STOCK with zero other activity:
     *  these rows have no business history beyond the receipt now being undone, so
     *  reversing it really is "this never happened" rather than erasing a real event. */
    @Transactional
    public void deletePiecesCreatedByPurchaseLine(long purchaseLineId) {
        List<Piece> pieces = pieceRepository.findByPurchaseLineId(purchaseLineId);
        // M11: validate every piece before deleting any of them. Photo files (unlike the DB
        // rows below) don't come back if the surrounding transaction rolls back, so a
        // single interleaved loop could leave an earlier piece's photo files deleted while
        // its DB rows survive a later piece's validation failure.
        for (Piece piece : pieces) {
            if (piece.state() != Piece.State.IN_STOCK) {
                throw new IllegalStateException("Piece " + piece.tag() + " is " + piece.state()
                        + " and cannot be removed by reversing the receipt.");
            }
        }
        for (Piece piece : pieces) {
            piecePhotoService.deleteAllForPiece(piece.id());
            stockMovementRepository.deleteByPieceId(piece.id());
            pieceRepository.delete(piece.id());
        }
    }

    /** Read-only classification of an item model's in-stock pieces, for the Item Model
     *  List's confirmation dialog to state exact counts before anything is touched -
     *  {@link #zeroStockForItemModel} re-classifies independently rather than trusting this
     *  preview, so a stale count here can under- or over-state the dialog but can never
     *  cause the wrong action to be taken. */
    public ZeroStockPreview previewZeroStock(long itemModelId) {
        List<Piece> pieces = pieceRepository.findInStockByItemModelId(itemModelId);
        int writeOffCount = 0;
        for (Piece piece : pieces) {
            if (pieceRepository.hasRetainedDocumentReference(piece.id())) {
                writeOffCount++;
            }
        }
        return new ZeroStockPreview(pieces.size() - writeOffCount, writeOffCount);
    }

    /** Zeroes an item model's In Stock count (owner request: "stock deletion", refined to
     *  "the count reads 0" rather than a blanket hard delete - see the class Javadoc's
     *  reasoning on why RETURNED_TO_SUPPLIER/SOLD pieces are already excluded by only
     *  considering IN_STOCK). A piece with no retained document reference is permanently
     *  deleted, the same teardown order as {@link #deletePiecesCreatedByPurchaseLine}; a
     *  piece a cancelled invoice or a sales/purchase return still points at is written off
     *  instead, through the same {@link #changeState} path the piece register's own Write
     *  Off button uses - deleting it would either violate the enforced foreign key or, if it
     *  somehow didn't, leave that retained document pointing at nothing. Either way the In
     *  Stock count reads 0; only the Piece Register shows the difference. */
    @Transactional
    public ZeroStockOutcome zeroStockForItemModel(long itemModelId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to zero stock.");
        }
        List<Piece> pieces = pieceRepository.findInStockByItemModelId(itemModelId);
        if (pieces.isEmpty()) {
            return new ZeroStockOutcome(0, 0);
        }

        // Classify every piece before touching any of them - same reasoning as
        // deletePiecesCreatedByPurchaseLine's own comment: photo files don't come back on a
        // rolled-back transaction, so a single interleaved loop could leave one piece's
        // photos deleted while a later piece's classification fails.
        List<Piece> toDelete = new ArrayList<>();
        List<Piece> toWriteOff = new ArrayList<>();
        for (Piece piece : pieces) {
            if (pieceRepository.hasRetainedDocumentReference(piece.id())) {
                toWriteOff.add(piece);
            } else {
                toDelete.add(piece);
            }
        }

        for (Piece piece : toDelete) {
            piecePhotoService.deleteAllForPiece(piece.id());
            stockMovementRepository.deleteByPieceId(piece.id());
            pieceRepository.delete(piece.id());
        }
        for (Piece piece : toWriteOff) {
            changeState(piece.id(), Piece.State.WRITTEN_OFF, reason);
        }

        ItemModel model = itemModelRepository.findById(itemModelId).orElseThrow();
        auditLogService.record("ZERO_STOCK", "ITEM_MODEL", itemModelId,
                "Zeroed stock for " + model.modelName() + " - " + toDelete.size() + " deleted, "
                        + toWriteOff.size() + " written off. Reason: " + reason);

        return new ZeroStockOutcome(toDelete.size(), toWriteOff.size());
    }

    /** IN_STOCK -> RETURNED_TO_SUPPLIER (FR-PUR-07), invoked by {@code PurchaseReturnService}
     *  with the debit note it belongs to - not reachable from the piece register's own
     *  manual actions, see the class Javadoc. */
    @Transactional
    public void markReturnedToSupplier(long pieceId, long purchaseReturnId) {
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));
        if (piece.state() != Piece.State.IN_STOCK) {
            throw new IllegalStateException(
                    "Piece " + piece.tag() + " is " + piece.state() + " and cannot be returned to the supplier.");
        }
        pieceRepository.updateState(pieceId, Piece.State.RETURNED_TO_SUPPLIER, null);
        stockMovementRepository.record(pieceId, StockMovement.Type.PURCHASE_RETURN, piece.state(),
                Piece.State.RETURNED_TO_SUPPLIER, null, null, "PURCHASE_RETURN", purchaseReturnId, null);
    }

    /** IN_STOCK -> SOLD (FR-SAL-08), invoked by {@code SalesInvoiceService} within the
     *  same transaction as saving the invoice - not reachable from the piece register's
     *  own manual actions, see the class Javadoc. */
    @Transactional
    public void markSold(long pieceId, long salesInvoiceId) {
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));
        if (piece.state() != Piece.State.IN_STOCK) {
            throw new IllegalStateException(
                    "Piece " + piece.tag() + " is " + piece.state() + " and cannot be sold.");
        }
        pieceRepository.updateState(pieceId, Piece.State.SOLD, null);
        stockMovementRepository.record(pieceId, StockMovement.Type.SALE, piece.state(), Piece.State.SOLD,
                null, null, "SALES_INVOICE", salesInvoiceId, null);
    }

    /** SOLD -> IN_STOCK via a sales return (FR-SAL-10), invoked by
     *  {@code SalesReturnService} with the credit note it belongs to. Distinct from
     *  {@link #markInvoiceCancelled} even though both land on the same target state,
     *  because they are different real-world events with different ref documents
     *  (docs/02-data-model.md section 3 lists both as separate SOLD -> IN_STOCK causes). */
    @Transactional
    public void markSalesReturned(long pieceId, long salesReturnId) {
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));
        if (piece.state() != Piece.State.SOLD) {
            throw new IllegalStateException(
                    "Piece " + piece.tag() + " is " + piece.state() + " and cannot be returned from a sale.");
        }
        pieceRepository.updateState(pieceId, Piece.State.IN_STOCK, null);
        stockMovementRepository.record(pieceId, StockMovement.Type.SALES_RETURN, piece.state(),
                Piece.State.IN_STOCK, null, null, "SALES_RETURN", salesReturnId, null);
    }

    /** SOLD -> IN_STOCK via cancelling the whole invoice (FR-SAL-11), invoked by
     *  {@code SalesInvoiceService}. See {@link #markSalesReturned} for why this is a
     *  separate method rather than the same one with a different ref type. */
    @Transactional
    public void markInvoiceCancelled(long pieceId, long salesInvoiceId) {
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));
        if (piece.state() != Piece.State.SOLD) {
            throw new IllegalStateException(
                    "Piece " + piece.tag() + " is " + piece.state() + " and cannot be restored by cancelling the invoice.");
        }
        pieceRepository.updateState(pieceId, Piece.State.IN_STOCK, null);
        stockMovementRepository.record(pieceId, StockMovement.Type.INVOICE_CANCELLED, piece.state(),
                Piece.State.IN_STOCK, null, null, "SALES_INVOICE", salesInvoiceId, null);
    }

    /** FR-PIECE-02: {@code <model code>-<zero-padded sequence>}, guaranteed unique. */
    public String generateTag(String modelCode) {
        String tag;
        do {
            long seq = sequenceCounterRepository.next("PIECE_TAG:" + modelCode, "");
            tag = modelCode + "-" + String.format("%04d", seq);
            // Loops only if a tag was previously entered manually (FR-PIECE-02 override)
            // and happens to collide with the next auto-generated number.
        } while (pieceRepository.existsByTag(tag));
        return tag;
    }

    /** The four manual state changes available from the piece register (FR-PIECE-04,
     *  FR-PIECE-08). A reason is mandatory for DAMAGED and WRITTEN_OFF and is written to
     *  the audit log; it is cleared when a piece returns to IN_STOCK. */
    @Transactional
    public void changeState(long pieceId, Piece.State newState, String reason) {
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));

        Set<Piece.State> allowed = MANUAL_TRANSITIONS.getOrDefault(piece.state(), Set.of());
        if (!allowed.contains(newState)) {
            throw new IllegalStateException(
                    "Piece " + piece.tag() + " is " + piece.state() + " and cannot be manually moved to "
                            + newState + " here.");
        }
        if ((newState == Piece.State.DAMAGED || newState == Piece.State.WRITTEN_OFF)
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required for this change.");
        }

        String storedReason = newState == Piece.State.IN_STOCK ? null : reason;
        pieceRepository.updateState(pieceId, newState, storedReason);

        StockMovement.Type movementType = movementTypeFor(piece.state(), newState);
        stockMovementRepository.record(pieceId, movementType, piece.state(), newState,
                null, null, null, null, reason);

        if (newState == Piece.State.DAMAGED || newState == Piece.State.WRITTEN_OFF) {
            auditLogService.record(movementType.name(), "PIECE", pieceId, reason);
        }
    }

    private static StockMovement.Type movementTypeFor(Piece.State from, Piece.State to) {
        if (to == Piece.State.DAMAGED) {
            return StockMovement.Type.DAMAGE;
        }
        if (to == Piece.State.WRITTEN_OFF) {
            return StockMovement.Type.WRITE_OFF;
        }
        if (to == Piece.State.IN_STOCK && from == Piece.State.DAMAGED) {
            return StockMovement.Type.REPAIR;
        }
        throw new IllegalStateException("Unhandled manual transition " + from + " -> " + to);
    }

    @Transactional
    public void changeLocation(long pieceId, long newLocationId) {
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));
        pieceRepository.updateLocation(pieceId, newLocationId);
        stockMovementRepository.record(pieceId, StockMovement.Type.LOCATION_CHANGE, piece.state(), piece.state(),
                piece.locationId(), newLocationId, null, null, null);
    }

    /** FR-PIECE-02's override clause: the auto-generated tag can be replaced, as long as
     *  the replacement is unique. */
    @Transactional
    public void renameTag(long pieceId, String newTag) {
        if (newTag == null || newTag.isBlank()) {
            throw new IllegalArgumentException("Tag cannot be blank.");
        }
        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new IllegalArgumentException("Piece not found."));
        String trimmed = newTag.trim();
        if (trimmed.equals(piece.tag())) {
            return;
        }
        if (pieceRepository.existsByTag(trimmed)) {
            throw new IllegalArgumentException("Tag \"" + trimmed + "\" is already in use.");
        }
        String oldTag = piece.tag();
        pieceRepository.renameTag(pieceId, trimmed);
        stockMovementRepository.record(pieceId, StockMovement.Type.TAG_RENAMED, piece.state(), piece.state(),
                null, null, null, null, "Renamed from " + oldTag + " to " + trimmed);
    }
}
