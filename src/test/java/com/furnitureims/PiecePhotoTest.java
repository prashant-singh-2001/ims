package com.furnitureims;

import com.furnitureims.config.AppPaths;
import com.furnitureims.config.TestAppPathsFactory;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.PiecePhoto;
import com.furnitureims.money.Money;
import com.furnitureims.service.CategoryService;
import com.furnitureims.service.ItemModelService;
import com.furnitureims.service.PiecePhotoService;
import com.furnitureims.service.PieceService;
import com.furnitureims.service.StorageLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M11: per-piece photos (FR-PIECE-10). Mirrors the shape of {@link M10GstOptionalTest} - a
 * real Spring context against an isolated temp SQLite DB, no mocking - since this is the
 * project's only test style. There is no equivalent test class for the sibling item-model
 * photo feature to compare against; this establishes coverage {@code ItemModelService}'s
 * {@code addPhoto}/{@code removePhoto}/{@code setPrimaryPhoto} never had.
 */
@SpringBootTest(classes = FurnitureImsApplication.class)
@Import(PiecePhotoTest.TestPathsConfig.class)
class PiecePhotoTest {

    @TestConfiguration
    static class TestPathsConfig {
        @Bean
        @Primary
        AppPaths testAppPaths() throws IOException {
            return TestAppPathsFactory.create(Files.createTempDirectory("furniture-ims-piece-photo-test-"));
        }
    }

    @Autowired private CategoryService categoryService;
    @Autowired private StorageLocationService storageLocationService;
    @Autowired private ItemModelService itemModelService;
    @Autowired private PieceService pieceService;
    @Autowired private PiecePhotoService piecePhotoService;
    @Autowired private AppPaths appPaths;

    private long newPieceId(String modelCode) {
        long categoryId = categoryService.listActive().stream()
                .filter(c -> c.name().equals("Chair")).findFirst().orElseThrow().id();
        long locationId = storageLocationService.listActive().stream()
                .filter(l -> l.name().equals("Godown")).findFirst().orElseThrow().id();
        long modelId = itemModelService.create(new ItemModel(0, modelCode, "Oak Dining Chair", categoryId,
                "9403", new BigDecimal("18"), null, null, null, null, null, null, null, true, null));
        return pieceService.createOpeningStock(modelId, 1, Money.ofRupees("500.00"), locationId, LocalDate.now())
                .get(0).id();
    }

    private static Path fakeImageFile(String suffix, int widthPx, int heightPx) throws IOException {
        BufferedImage image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
        Path file = Files.createTempFile("piece-photo-source-", suffix);
        ImageIO.write(image, suffix.endsWith("png") ? "png" : "jpg", file.toFile());
        return file;
    }

    private static BufferedImage readStored(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    // ---- addPhoto -----------------------------------------------------------------------

    @Test
    void firstPhotoAddedBecomesPrimaryAutomatically() throws IOException {
        long pieceId = newPieceId("PHOTO1");
        PiecePhoto photo = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 200, 200));

        assertTrue(photo.primary());
        List<PiecePhoto> photos = piecePhotoService.photosFor(pieceId);
        assertEquals(1, photos.size());
        assertTrue(photos.get(0).primary());
    }

    @Test
    void secondAndLaterPhotosAreNotPrimaryByDefault() throws IOException {
        long pieceId = newPieceId("PHOTO2");
        PiecePhoto first = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 200, 200));
        PiecePhoto second = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 200, 200));

        assertTrue(first.primary());
        assertFalse(second.primary());
    }

    @Test
    void largeImageIsDownscaledTo1600pxLongEdge() throws IOException {
        long pieceId = newPieceId("PHOTO3");
        PiecePhoto photo = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 2000, 1000));

        BufferedImage stored = readStored(piecePhotoService.photoFile(photo));
        assertEquals(1600, stored.getWidth());
        assertEquals(800, stored.getHeight());
    }

    @Test
    void smallImageIsStoredUnchangedInDimensions() throws IOException {
        long pieceId = newPieceId("PHOTO4");
        PiecePhoto photo = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 200, 100));

        BufferedImage stored = readStored(piecePhotoService.photoFile(photo));
        assertEquals(200, stored.getWidth());
        assertEquals(100, stored.getHeight());
    }

    @Test
    void pngSourceKeepsPngExtensionJpgIsTheDefaultForOthers() throws IOException {
        long pieceId = newPieceId("PHOTO5");
        PiecePhoto pngPhoto = piecePhotoService.addPhoto(pieceId, fakeImageFile(".png", 100, 100));
        PiecePhoto jpgPhoto = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));

        assertTrue(pngPhoto.relativePath().endsWith(".png"));
        assertTrue(jpgPhoto.relativePath().endsWith(".jpg"));
    }

    @Test
    void addingMoreThanMaxPhotosIsRejected() throws IOException {
        long pieceId = newPieceId("PHOTO6");
        piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));

        Path fourth = fakeImageFile(".jpg", 100, 100);
        assertThrows(IllegalStateException.class, () -> piecePhotoService.addPhoto(pieceId, fourth));
        assertEquals(3, piecePhotoService.photosFor(pieceId).size());
    }

    @Test
    void photoFileLivesUnderPhotosPiecesSubfolder() throws IOException {
        long pieceId = newPieceId("PHOTO7");
        PiecePhoto photo = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));

        Path expectedDir = appPaths.photos().resolve("pieces").resolve(String.valueOf(pieceId));
        assertEquals(expectedDir, piecePhotoService.photoFile(photo).getParent());
    }

    // ---- removePhoto --------------------------------------------------------------------

    @Test
    void removingThePrimaryPhotoPromotesTheNextOneBySortOrder() throws IOException {
        long pieceId = newPieceId("PHOTO8");
        PiecePhoto first = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        PiecePhoto second = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));

        piecePhotoService.removePhoto(first);

        List<PiecePhoto> remaining = piecePhotoService.photosFor(pieceId);
        assertEquals(1, remaining.size());
        assertEquals(second.id(), remaining.get(0).id());
        assertTrue(remaining.get(0).primary());
    }

    @Test
    void removingANonPrimaryPhotoLeavesThePrimaryUnchanged() throws IOException {
        long pieceId = newPieceId("PHOTO9");
        PiecePhoto first = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        PiecePhoto second = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));

        piecePhotoService.removePhoto(second);

        List<PiecePhoto> remaining = piecePhotoService.photosFor(pieceId);
        assertEquals(1, remaining.size());
        assertEquals(first.id(), remaining.get(0).id());
        assertTrue(remaining.get(0).primary());
    }

    @Test
    void removingAPhotoDeletesItsFileFromDisk() throws IOException {
        long pieceId = newPieceId("PHOTO10");
        PiecePhoto photo = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        Path file = piecePhotoService.photoFile(photo);
        assertTrue(Files.exists(file));

        piecePhotoService.removePhoto(photo);

        assertFalse(Files.exists(file));
    }

    // ---- setPrimaryPhoto ------------------------------------------------------------------

    @Test
    void setPrimaryPhotoSwitchesWhichOneIsPrimary() throws IOException {
        long pieceId = newPieceId("PHOTO11");
        PiecePhoto first = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        PiecePhoto second = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));

        piecePhotoService.setPrimaryPhoto(second);

        List<PiecePhoto> photos = piecePhotoService.photosFor(pieceId);
        PiecePhoto reloadedFirst = photos.stream().filter(p -> p.id() == first.id()).findFirst().orElseThrow();
        PiecePhoto reloadedSecond = photos.stream().filter(p -> p.id() == second.id()).findFirst().orElseThrow();
        assertFalse(reloadedFirst.primary());
        assertTrue(reloadedSecond.primary());
    }

    // ---- deleteAllForPiece (the PieceService.deletePiecesCreatedByPurchaseLine hook) -----

    @Test
    void deleteAllForPieceRemovesBothRowsAndFiles() throws IOException {
        long pieceId = newPieceId("PHOTO12");
        PiecePhoto first = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        PiecePhoto second = piecePhotoService.addPhoto(pieceId, fakeImageFile(".jpg", 100, 100));
        Path firstFile = piecePhotoService.photoFile(first);
        Path secondFile = piecePhotoService.photoFile(second);

        piecePhotoService.deleteAllForPiece(pieceId);

        assertTrue(piecePhotoService.photosFor(pieceId).isEmpty());
        assertFalse(Files.exists(firstFile));
        assertFalse(Files.exists(secondFile));
    }

}
