package com.piecetrack.service;

import com.piecetrack.domain.SalesLine;
import com.piecetrack.repository.BookingListRow;
import com.piecetrack.repository.BookingRepository;
import com.piecetrack.repository.SalesLineRepository;
import com.piecetrack.repository.SalesReturnLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FR-SAL-13 (M13): a "booking" is an existing {@code ACTIVE} invoice viewed by which of its
 * items have physically reached the customer - not a new pre-invoice entity, and not a new
 * {@link com.piecetrack.domain.Piece.State}. The billing/advance/GST work is already done
 * by the time an invoice exists; this only tracks the fulfilment fact left over afterwards.
 * <p>
 * Kept separate from {@link SalesInvoiceService}, which is already large, the same way M11
 * kept {@link PiecePhotoService} out of {@link PieceService}.
 */
@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final SalesLineRepository salesLineRepository;
    private final SalesReturnLineRepository salesReturnLineRepository;
    private final AuditLogService auditLogService;

    public BookingService(BookingRepository bookingRepository, SalesLineRepository salesLineRepository,
                           SalesReturnLineRepository salesReturnLineRepository, AuditLogService auditLogService) {
        this.bookingRepository = bookingRepository;
        this.salesLineRepository = salesLineRepository;
        this.salesReturnLineRepository = salesReturnLineRepository;
        this.auditLogService = auditLogService;
    }

    public List<BookingListRow> listBookings(boolean pendingOnly) {
        return pendingOnly ? bookingRepository.findPending() : bookingRepository.findAll();
    }

    /** Excludes any line already sales-returned (mirrors {@link BookingRepository}'s own
     *  {@code NOT EXISTS} - a returned piece is not part of this booking any more), so this
     *  screen's item list and its "N of M delivered" count always agree with the list
     *  screen's counts for the same invoice. */
    public List<SalesLine> linesFor(long salesInvoiceId) {
        return salesLineRepository.findBySalesInvoiceId(salesInvoiceId).stream()
                .filter(line -> !salesReturnLineRepository.existsBySalesLineId(line.id()))
                .toList();
    }

    @Transactional
    public void markDelivered(long salesLineId) {
        salesLineRepository.updateDeliveredAt(salesLineId, LocalDateTime.now());
        auditLogService.record("DELIVERY_MARKED", "SALES_LINE", salesLineId, "Marked delivered");
    }

    /** Clearing a mis-tick is a correction, not an invoice edit - FR-SAL-12's no-edit rule
     *  governs the invoice itself, not this fulfilment fact recorded alongside it. */
    @Transactional
    public void markNotDelivered(long salesLineId) {
        salesLineRepository.updateDeliveredAt(salesLineId, null);
        auditLogService.record("DELIVERY_UNMARKED", "SALES_LINE", salesLineId, "Marked not delivered");
    }

    /** Only stamps lines not already delivered, so the audit log records exactly which
     *  pieces this action actually changed. */
    @Transactional
    public void markAllDelivered(long salesInvoiceId) {
        LocalDateTime now = LocalDateTime.now();
        for (SalesLine line : linesFor(salesInvoiceId)) {
            if (line.deliveredAt() == null) {
                salesLineRepository.updateDeliveredAt(line.id(), now);
                auditLogService.record("DELIVERY_MARKED", "SALES_LINE", line.id(), "Marked delivered (mark all)");
            }
        }
    }
}
