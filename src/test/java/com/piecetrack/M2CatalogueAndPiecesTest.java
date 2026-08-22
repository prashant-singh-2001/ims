package com.piecetrack;

import com.piecetrack.config.AppPaths;
import com.piecetrack.config.TestAppPathsFactory;
import com.piecetrack.domain.ItemModel;
import com.piecetrack.domain.Piece;
import com.piecetrack.domain.StockMovement;
import com.piecetrack.money.Money;
import com.piecetrack.repository.ItemModelSearchCriteria;
import com.piecetrack.repository.ItemModelSummary;
import com.piecetrack.repository.PieceSearchCriteria;
import com.piecetrack.repository.PieceSummary;
import com.piecetrack.service.CategoryService;
import com.piecetrack.service.ItemModelService;
import com.piecetrack.service.PieceService;
import com.piecetrack.service.StorageLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises milestone M2's business logic against a real (temp-directory) SQLite
 * database with Flyway migrations applied - no mocking of the piece state machine or the
 * landed-cost/tag-generation logic, since those are exactly the parts most worth getting
 * right. UI screens are not exercised here; this is the layer beneath them.
 * <p>
 * {@code @Import} is required here, not just the nested {@code @TestConfiguration} class
 * on its own: Spring Boot only auto-merges a nested {@code @TestConfiguration} when it is
 * the one discovering the app's configuration classes itself, which explicitly passing
 * {@code classes = PieceTrackApplication.class} below opts out of - without the
 * {@code @Import}, the real {@link AppPaths} bean (pointed at the real
 * {@code %LOCALAPPDATA%}) is the only candidate and every test runs against production
 * data. Confirmed the hard way once; do not remove this import.
 */
@SpringBootTest(classes = PieceTrackApplication.class)
@Import(M2CatalogueAndPiecesTest.TestPathsConfig.class)
class M2CatalogueAndPiecesTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            Path tempDir = Files.createTempDirectory("piecetrack-test-");
            return TestAppPathsFactory.create(tempDir);
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;

    private long chairCategoryId() {
        return categoryService.listActive().stream()
                .filter(c -> c.name().equals("Other")).findFirst().orElseThrow().id();
    }

    private long godownLocationId() {
        return storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst()
                .map(l -> l.id())
                .orElseGet(() -> storageLocationService.create("Godown"));
    }

    private long createChairModel(String code) {
        ItemModel draft = new ItemModel(0, code, "Test Widget", chairCategoryId(), "9403",
                new BigDecimal("18"), null, true, null);
        return itemModelService.create(draft);
    }

    @Test
    void openingStockCreatesUniquelyTaggedCorrectlyCostedPieces() {
        long modelId = createChairModel("CHRA");
        long locationId = godownLocationId();

        List<Piece> created = pieceService.createOpeningStock(
                modelId, 6, Money.ofRupees("3180.00"), locationId, LocalDate.now());

        assertThat(created).hasSize(6);
        assertThat(created.stream().map(Piece::tag).distinct()).hasSize(6);
        assertThat(created).allMatch(p -> p.tag().startsWith("CHRA-"));
        assertThat(created).allMatch(p -> p.landedCost().equals(Money.ofRupees("3180.00")));
        assertThat(created).allMatch(p -> p.state() == Piece.State.IN_STOCK);
        assertThat(created).allMatch(p -> p.sourceType() == Piece.SourceType.OPENING_STOCK);

        ItemModelSummary summary = itemModelService.search(ItemModelSearchCriteria.defaultCriteria()).stream()
                .filter(s -> s.model().id() == modelId).findFirst().orElseThrow();
        assertEquals(6, summary.inStockCount());
    }

    @Test
    void manualStateTransitionsAreEnforcedAndHistoryIsRecorded() {
        long modelId = createChairModel("CHRB");
        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("2500.00"), godownLocationId(), LocalDate.now()).get(0);

        pieceService.changeState(piece.id(), Piece.State.DAMAGED, "Scratched during unloading");
        Piece damaged = pieceService.findById(piece.id()).orElseThrow();
        assertEquals(Piece.State.DAMAGED, damaged.state());
        assertEquals("Scratched during unloading", damaged.stateReason());

        // Not a manual transition this screen can perform - selling happens through M4.
        assertThrows(IllegalStateException.class,
                () -> pieceService.changeState(piece.id(), Piece.State.SOLD, null));

        // A reason is mandatory for damage/write-off.
        Piece freshPiece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("2500.00"), godownLocationId(), LocalDate.now()).get(0);
        assertThrows(IllegalArgumentException.class,
                () -> pieceService.changeState(freshPiece.id(), Piece.State.DAMAGED, "  "));

        pieceService.changeState(piece.id(), Piece.State.IN_STOCK, null); // repaired
        Piece repaired = pieceService.findById(piece.id()).orElseThrow();
        assertEquals(Piece.State.IN_STOCK, repaired.state());
        assertNull(repaired.stateReason());

        List<StockMovement> history = pieceService.history(piece.id());
        assertThat(history).extracting(StockMovement::movementType)
                .containsExactly(StockMovement.Type.OPENING, StockMovement.Type.DAMAGE, StockMovement.Type.REPAIR);
    }

    @Test
    void tagRenameEnforcesUniqueness() {
        long modelId = createChairModel("CHRC");
        List<Piece> pieces = pieceService.createOpeningStock(
                modelId, 2, Money.ofRupees("1000.00"), godownLocationId(), LocalDate.now());
        Piece a = pieces.get(0);
        Piece b = pieces.get(1);

        assertThrows(IllegalArgumentException.class, () -> pieceService.renameTag(a.id(), b.tag()));

        pieceService.renameTag(a.id(), "CUSTOM-001");
        assertEquals("CUSTOM-001", pieceService.findById(a.id()).orElseThrow().tag());
    }

    @Test
    void locationChangeIsRecordedAsAMovement() {
        long modelId = createChairModel("CHRD");
        long godownId = godownLocationId();
        long showroomId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Showroom Floor")).findFirst()
                .map(l -> l.id())
                .orElseGet(() -> storageLocationService.create("Showroom Floor"));

        Piece piece = pieceService.createOpeningStock(
                modelId, 1, Money.ofRupees("1500.00"), godownId, LocalDate.now()).get(0);

        pieceService.changeLocation(piece.id(), showroomId);

        assertEquals(showroomId, pieceService.findById(piece.id()).orElseThrow().locationId());
        List<StockMovement> history = pieceService.history(piece.id());
        StockMovement locationChange = history.get(history.size() - 1);
        assertEquals(StockMovement.Type.LOCATION_CHANGE, locationChange.movementType());
        assertEquals(godownId, locationChange.fromLocationId());
        assertEquals(showroomId, locationChange.toLocationId());
    }

    @Test
    void itemModelWithPiecesCannotBeDeletedOnlyDiscontinued() {
        long modelId = createChairModel("CHRE");
        pieceService.createOpeningStock(modelId, 1, Money.ofRupees("1000.00"), godownLocationId(), LocalDate.now());

        assertThrows(IllegalStateException.class, () -> itemModelService.delete(modelId));

        itemModelService.setActive(modelId, false);
        assertFalse(itemModelService.findById(modelId).orElseThrow().active());
    }

    @Test
    void pieceSearchFiltersByStateAndModel() {
        long modelId = createChairModel("CHRF");
        List<Piece> pieces = pieceService.createOpeningStock(
                modelId, 3, Money.ofRupees("1200.00"), godownLocationId(), LocalDate.now());
        pieceService.changeState(pieces.get(0).id(), Piece.State.WRITTEN_OFF, "Beyond repair");

        List<PieceSummary> inStock = pieceService.search(
                new PieceSearchCriteria(null, Piece.State.IN_STOCK, modelId, null, null, null, null));
        List<PieceSummary> writtenOff = pieceService.search(
                new PieceSearchCriteria(null, Piece.State.WRITTEN_OFF, modelId, null, null, null, null));

        assertEquals(2, inStock.size());
        assertEquals(1, writtenOff.size());
    }

    @Test
    void categoryAndLocationNamesMustBeUnique() {
        categoryService.create("Bespoke Test Category");
        assertThrows(IllegalArgumentException.class, () -> categoryService.create("Bespoke Test Category"));

        storageLocationService.create("Bespoke Test Location");
        assertThrows(IllegalArgumentException.class, () -> storageLocationService.create("Bespoke Test Location"));
    }
}
