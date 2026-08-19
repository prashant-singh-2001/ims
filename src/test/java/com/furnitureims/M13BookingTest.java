package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.Customer;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.Piece;
import com.furnitureims.domain.SalesInvoice;
import com.furnitureims.domain.SalesLine;
import com.furnitureims.domain.SalesReturn;
import com.furnitureims.domain.ShopProfile;
import com.furnitureims.money.Money;
import com.furnitureims.repository.AuditLogRepository;
import com.furnitureims.repository.AuditLogSearchCriteria;
import com.furnitureims.repository.BookingListRow;
import com.furnitureims.repository.ShopProfileRepository;
import com.furnitureims.service.BookingService;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.CustomerService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.SalesInvoiceService;
import com.furnitureims.service.SalesReturnService;
import com.furnitureims.service.StorageLocationService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone M13 (Bookings tab, FR-SAL-13): a booking is an existing {@code ACTIVE} invoice
 * viewed by which of its items have been delivered - there is no separate booking entity, so
 * these tests exercise {@link BookingService} against real invoices created the same way
 * {@code M4SalesTest} does. The two edge cases the plan calls out explicitly - a cancelled
 * invoice, and a piece returned before delivery - get their own tests, since those are the
 * regressions this milestone is most likely to get wrong.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(M13BookingTest.TestPathsConfig.class)
class M13BookingTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-m13-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private CustomerService customerService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private SalesReturnService salesReturnService;
    @Autowired private BookingService bookingService;
    @Autowired private AuditLogRepository auditLogRepository;
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
        return customerService.create(
                new Customer(0, name, phone, null, null, null, null, null, null, null, null, null));
    }

    private long invoiceOf(long customerId, Piece... pieces) {
        List<SalesInvoiceService.InvoiceLineInput> lines = java.util.Arrays.stream(pieces)
                .map(p -> new SalesInvoiceService.InvoiceLineInput(p.id(), Money.ofRupees("2000.00"), Money.ZERO))
                .toList();
        return salesInvoiceService.createInvoice(customerId, "27", false, lines, Money.ZERO, LocalDate.now());
    }

    private Optional<BookingListRow> bookingFor(long invoiceId, boolean pendingOnly) {
        return bookingService.listBookings(pendingOnly).stream()
                .filter(b -> b.invoiceId() == invoiceId).findFirst();
    }

    @Test
    void freshInvoiceAppearsAsAPendingBookingWithNothingDelivered() {
        long modelId = chairModelId("BOOKA");
        Piece pieceA = newPiece(modelId, "1000.00");
        Piece pieceB = newPiece(modelId, "1000.00");
        long customerId = customerId("Pending Test", "9000001001");

        long invoiceId = invoiceOf(customerId, pieceA, pieceB);

        BookingListRow booking = bookingFor(invoiceId, true).orElseThrow();
        assertEquals(2, booking.totalItems());
        assertEquals(0, booking.deliveredItems());
    }

    @Test
    void markingOneLineDeliveredMakesItPartlyDeliveredAndMarkingAllCompletesIt() {
        long modelId = chairModelId("BOOKB");
        Piece pieceA = newPiece(modelId, "1000.00");
        Piece pieceB = newPiece(modelId, "1000.00");
        long customerId = customerId("Partial Test", "9000001002");
        long invoiceId = invoiceOf(customerId, pieceA, pieceB);

        SalesLine lineA = bookingService.linesFor(invoiceId).get(0);
        bookingService.markDelivered(lineA.id());

        BookingListRow partial = bookingFor(invoiceId, true).orElseThrow();
        assertEquals(1, partial.deliveredItems());
        assertEquals(2, partial.totalItems());

        bookingService.markAllDelivered(invoiceId);

        assertTrue(bookingFor(invoiceId, true).isEmpty(),
                "a fully delivered booking must drop out of the pending-only list");
        BookingListRow complete = bookingFor(invoiceId, false).orElseThrow();
        assertEquals(2, complete.deliveredItems());
    }

    @Test
    void markingNotDeliveredClearsTheStampAndReturnsToThePendingList() {
        long modelId = chairModelId("BOOKC");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Unmark Test", "9000001003");
        long invoiceId = invoiceOf(customerId, piece);

        SalesLine line = bookingService.linesFor(invoiceId).get(0);
        bookingService.markDelivered(line.id());
        assertTrue(bookingFor(invoiceId, true).isEmpty(), "fully delivered - not in the pending list");

        bookingService.markNotDelivered(line.id());

        BookingListRow pending = bookingFor(invoiceId, true).orElseThrow();
        assertEquals(0, pending.deliveredItems());
    }

    @Test
    void cancelledInvoiceDoesNotAppearAsABookingAtAll() {
        long modelId = chairModelId("BOOKD");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Cancel Booking Test", "9000001004");
        long invoiceId = invoiceOf(customerId, piece);

        assertTrue(bookingFor(invoiceId, false).isPresent(), "should be a booking before cancellation");

        salesInvoiceService.cancelInvoice(invoiceId, "Customer changed their mind");

        assertTrue(bookingFor(invoiceId, false).isEmpty(),
                "a cancelled invoice must not appear as a booking, pending or otherwise");
    }

    @Test
    void pieceReturnedBeforeDeliveryIsExcludedFromBothCountsSoTheBookingCanStillComplete() {
        long modelId = chairModelId("BOOKE");
        Piece pieceA = newPiece(modelId, "1000.00");
        Piece pieceB = newPiece(modelId, "1000.00");
        long customerId = customerId("Return Before Delivery Test", "9000001005");
        long invoiceId = invoiceOf(customerId, pieceA, pieceB);

        // Return piece A before it was ever delivered - it must vanish from the booking
        // entirely, not sit there forever as "undelivered", or the booking could never
        // reach Delivered.
        salesReturnService.createReturn(invoiceId, List.of(pieceA.id()), "Wrong colour", LocalDate.now(),
                SalesReturn.RefundMode.CASH_REFUND);

        BookingListRow afterReturn = bookingFor(invoiceId, true).orElseThrow();
        assertEquals(1, afterReturn.totalItems(), "the returned piece must not count toward the total");
        assertEquals(0, afterReturn.deliveredItems());

        List<SalesLine> remainingLines = bookingService.linesFor(invoiceId);
        assertEquals(1, remainingLines.size(), "the returned line must not appear in the detail screen either");

        bookingService.markDelivered(remainingLines.get(0).id());

        assertTrue(bookingFor(invoiceId, true).isEmpty(),
                "with the returned piece excluded, delivering the one remaining piece must complete the booking");
    }

    @Test
    void bothMarkDeliveredAndMarkNotDeliveredWriteAnAuditRow() {
        long modelId = chairModelId("BOOKF");
        Piece piece = newPiece(modelId, "1000.00");
        long customerId = customerId("Audit Test", "9000001006");
        long invoiceId = invoiceOf(customerId, piece);
        SalesLine line = bookingService.linesFor(invoiceId).get(0);

        bookingService.markDelivered(line.id());
        bookingService.markNotDelivered(line.id());

        List<com.furnitureims.domain.AuditLog> markedRows = auditLogRepository.search(
                new AuditLogSearchCriteria(null, null, "DELIVERY_MARKED", "SALES_LINE"), 0);
        List<com.furnitureims.domain.AuditLog> unmarkedRows = auditLogRepository.search(
                new AuditLogSearchCriteria(null, null, "DELIVERY_UNMARKED", "SALES_LINE"), 0);

        assertTrue(markedRows.stream().anyMatch(a -> line.id() == (a.entityId() == null ? -1 : a.entityId())),
                "marking delivered should write a DELIVERY_MARKED audit row for this line");
        assertTrue(unmarkedRows.stream().anyMatch(a -> line.id() == (a.entityId() == null ? -1 : a.entityId())),
                "marking not delivered should write a DELIVERY_UNMARKED audit row for this line");
    }
}
