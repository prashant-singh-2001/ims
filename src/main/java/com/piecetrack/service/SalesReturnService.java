package com.piecetrack.service;

import com.piecetrack.domain.Piece;
import com.piecetrack.domain.SalesInvoice;
import com.piecetrack.domain.SalesLine;
import com.piecetrack.domain.SalesReturn;
import com.piecetrack.domain.SalesReturnLine;
import com.piecetrack.money.Money;
import com.piecetrack.repository.SalesLineRepository;
import com.piecetrack.repository.SalesReturnLineRepository;
import com.piecetrack.repository.SalesReturnRepository;
import com.piecetrack.repository.SequenceCounterRepository;
import com.piecetrack.util.FinancialYear;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sales returns (FR-SAL-10). Unlike {@code PurchaseReturnService}, no apportionment is
 * needed here: a {@code sales_line} is already exactly one piece (FR-SAL-01), so a credit
 * note simply reuses that line's own taxable value and tax as-is.
 */
@Service
public class SalesReturnService {

    private final SalesReturnRepository salesReturnRepository;
    private final SalesReturnLineRepository salesReturnLineRepository;
    private final SalesLineRepository salesLineRepository;
    private final SalesInvoiceService salesInvoiceService;
    private final PieceService pieceService;
    private final SequenceCounterRepository sequenceCounterRepository;

    public SalesReturnService(SalesReturnRepository salesReturnRepository,
                               SalesReturnLineRepository salesReturnLineRepository,
                               SalesLineRepository salesLineRepository, SalesInvoiceService salesInvoiceService,
                               PieceService pieceService, SequenceCounterRepository sequenceCounterRepository) {
        this.salesReturnRepository = salesReturnRepository;
        this.salesReturnLineRepository = salesReturnLineRepository;
        this.salesLineRepository = salesLineRepository;
        this.salesInvoiceService = salesInvoiceService;
        this.pieceService = pieceService;
        this.sequenceCounterRepository = sequenceCounterRepository;
    }

    public List<SalesReturn> returnsFor(long salesInvoiceId) {
        return salesReturnRepository.findBySalesInvoiceId(salesInvoiceId);
    }

    @Transactional
    public long createReturn(long salesInvoiceId, List<Long> pieceIds, String reason, LocalDate returnDate,
                              SalesReturn.RefundMode refundMode) {
        if (pieceIds == null || pieceIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one piece to return.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required.");
        }
        SalesInvoice invoice = salesInvoiceService.findById(salesInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found."));
        if (invoice.status() != SalesInvoice.Status.ACTIVE) {
            throw new IllegalStateException("Only an active invoice has pieces that can be returned.");
        }

        // Every piece is checked - not IN_STOCK/SOLD elsewhere, and genuinely belongs to
        // this invoice - before any write, matching the fail-fast lesson from
        // PurchaseReturnService (checking too late surfaced as a raw SQL error there).
        List<SalesLine> linesToReturn = new ArrayList<>();
        for (Long pieceId : pieceIds) {
            SalesLine line = salesLineRepository.findByPieceId(pieceId)
                    .filter(l -> l.salesInvoiceId() == salesInvoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("This piece was not sold on this invoice."));
            Piece piece = pieceService.findById(pieceId)
                    .orElseThrow(() -> new IllegalArgumentException("Piece not found: " + pieceId));
            if (piece.state() != Piece.State.SOLD) {
                throw new IllegalStateException(
                        "Piece " + piece.tag() + " is " + piece.state() + " and cannot be returned.");
            }
            linesToReturn.add(line);
        }

        Money totalTaxable = Money.ZERO;
        Money totalCgst = Money.ZERO;
        Money totalSgst = Money.ZERO;
        Money totalIgst = Money.ZERO;
        for (SalesLine line : linesToReturn) {
            totalTaxable = totalTaxable.plus(line.taxableValue());
            totalCgst = totalCgst.plus(line.cgstAmount());
            totalSgst = totalSgst.plus(line.sgstAmount());
            totalIgst = totalIgst.plus(line.igstAmount());
        }
        Money totalAmount = totalTaxable.plus(totalCgst).plus(totalSgst).plus(totalIgst);

        String creditNoteNo = generateCreditNoteNo();
        SalesReturn toSave = new SalesReturn(0, salesInvoiceId, creditNoteNo, returnDate, reason.trim(),
                totalTaxable, totalCgst, totalSgst, totalIgst, totalAmount, refundMode, null);
        long returnId = salesReturnRepository.create(toSave);

        for (SalesLine line : linesToReturn) {
            salesReturnLineRepository.create(new SalesReturnLine(0, returnId, line.pieceId(), line.id(),
                    line.taxableValue(), line.cgstAmount(), line.sgstAmount(), line.igstAmount(),
                    line.lineTotal()));
            pieceService.markSalesReturned(line.pieceId(), returnId);
        }

        return returnId;
    }

    private String generateCreditNoteNo() {
        String fy = FinancialYear.current();
        long seq = sequenceCounterRepository.next("CREDIT_NOTE", fy);
        return "CN/" + fy + "/" + String.format("%04d", seq);
    }
}
