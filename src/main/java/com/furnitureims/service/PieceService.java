package com.furnitureims.service;

import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.StockMovement;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ItemModelRepository;
import com.furnitureims.repository.PieceRepository;
import com.furnitureims.repository.PieceSearchCriteria;
import com.furnitureims.repository.PieceSummary;
import com.furnitureims.repository.StockMovementRepository;
import com.furnitureims.repository.SequenceCounterRepository;
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
 * purchase modules instead, once they exist (milestones M3/M4) - those need a
 * ref_type/ref_id pointing at the invoice or bill that caused them, which a generic
 * "change state" call from this screen has no way to supply correctly.
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

    public PieceService(PieceRepository pieceRepository, StockMovementRepository stockMovementRepository,
                         SequenceCounterRepository sequenceCounterRepository,
                         ItemModelRepository itemModelRepository, AuditLogService auditLogService) {
        this.pieceRepository = pieceRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.sequenceCounterRepository = sequenceCounterRepository;
        this.itemModelRepository = itemModelRepository;
        this.auditLogService = auditLogService;
    }

    public Optional<Piece> findById(long id) {
        return pieceRepository.findById(id);
    }

    public List<PieceSummary> search(PieceSearchCriteria criteria) {
        return pieceRepository.search(criteria);
    }

    public List<StockMovement> history(long pieceId) {
        return stockMovementRepository.findByPieceId(pieceId);
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
            String tag = generateTag(model.modelCode());
            Piece toInsert = new Piece(0, tag, itemModelId, Piece.SourceType.OPENING_STOCK, null, unitCost,
                    locationId, Piece.State.IN_STOCK, null, acquiredOn, null, null);
            long id = pieceRepository.create(toInsert);
            stockMovementRepository.record(id, StockMovement.Type.OPENING, null, Piece.State.IN_STOCK,
                    null, locationId, "OPENING_STOCK", null, "Opening stock entry");
            created.add(pieceRepository.findById(id).orElseThrow());
        }
        return created;
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
