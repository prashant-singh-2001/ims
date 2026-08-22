package com.piecetrack.service;

import com.piecetrack.config.AppPaths;
import com.piecetrack.domain.PiecePhoto;
import com.piecetrack.repository.PiecePhotoRepository;
import com.piecetrack.util.ImageResizer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Per-piece photo management (FR-PIECE-10) - mirrors {@link ItemModelService}'s item-photo
 * methods one level down: a piece's actual physical condition (scuffs, wear, the specific
 * unit a customer is looking at) can differ from its siblings even though they share an
 * item model, so photos are captured per piece, not per model. Photos live on disk under
 * {@code Photos/pieces/<piece.id>/} - note the extra "pieces" segment, absent from
 * {@code Photos/<item_model.id>/} - without it a piece id and an item model id that happen
 * to be numerically equal would collide in the same folder. See docs/02-data-model.md
 * section 6.
 * <p>
 * Deliberately a separate service from {@link PieceService} rather than folded into it:
 * PieceService already owns the piece state machine and is injected by several other
 * transactional services (PurchaseBillService, SalesInvoiceService and others) that have no
 * need for photo/file-I/O concerns - this keeps that dependency surface unchanged, the same
 * way StockMovementRepository stays a separate collaborator from PieceRepository rather than
 * being merged into it.
 */
@Service
public class PiecePhotoService {

    private static final int MAX_PHOTOS = 3;

    private final PiecePhotoRepository piecePhotoRepository;
    private final AppPaths appPaths;

    public PiecePhotoService(PiecePhotoRepository piecePhotoRepository, AppPaths appPaths) {
        this.piecePhotoRepository = piecePhotoRepository;
        this.appPaths = appPaths;
    }

    public List<PiecePhoto> photosFor(long pieceId) {
        return piecePhotoRepository.findByPieceId(pieceId);
    }

    public Path photoFile(PiecePhoto photo) {
        return pieceDir(photo.pieceId()).resolve(photo.relativePath());
    }

    public PiecePhoto addPhoto(long pieceId, Path sourceFile) {
        List<PiecePhoto> existing = piecePhotoRepository.findByPieceId(pieceId);
        if (existing.size() >= MAX_PHOTOS) {
            throw new IllegalStateException("A piece can have at most " + MAX_PHOTOS + " photos.");
        }

        Path pieceDir = pieceDir(pieceId);
        try {
            Files.createDirectories(pieceDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String extension = sourceFile.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
        String relativePath = UUID.randomUUID() + extension;
        ImageResizer.resizeAndSave(sourceFile, pieceDir.resolve(relativePath));

        boolean primary = existing.isEmpty();
        long photoId = piecePhotoRepository.create(pieceId, relativePath, existing.size(), primary);
        return new PiecePhoto(photoId, pieceId, relativePath, existing.size(), primary);
    }

    public void removePhoto(PiecePhoto photo) {
        deletePhotoFile(photo);
        piecePhotoRepository.delete(photo.id());

        if (photo.primary()) {
            List<PiecePhoto> remaining = piecePhotoRepository.findByPieceId(photo.pieceId());
            if (!remaining.isEmpty()) {
                piecePhotoRepository.setPrimary(photo.pieceId(), remaining.get(0).id());
            }
        }
    }

    public void setPrimaryPhoto(PiecePhoto photo) {
        piecePhotoRepository.setPrimary(photo.pieceId(), photo.id());
    }

    /** Used only by {@code PieceService.deletePiecesCreatedByPurchaseLine}'s hard-delete path
     *  (FR-PUR-08) - deletes every photo (file and row) for a piece that is itself about to
     *  be hard-deleted, the same way {@code ItemModelService.delete()} cleans up item_photo
     *  rows before deleting the model. */
    public void deleteAllForPiece(long pieceId) {
        for (PiecePhoto photo : piecePhotoRepository.findByPieceId(pieceId)) {
            deletePhotoFile(photo);
            piecePhotoRepository.delete(photo.id());
        }
    }

    private Path pieceDir(long pieceId) {
        return appPaths.photos().resolve("pieces").resolve(String.valueOf(pieceId));
    }

    private void deletePhotoFile(PiecePhoto photo) {
        try {
            Files.deleteIfExists(photoFile(photo));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
