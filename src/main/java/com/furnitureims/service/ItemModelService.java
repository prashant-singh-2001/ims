package com.furnitureims.service;

import com.furnitureims.config.AppPaths;
import com.furnitureims.domain.ItemModel;
import com.furnitureims.domain.ItemPhoto;
import com.furnitureims.repository.ItemModelRepository;
import com.furnitureims.repository.ItemModelSearchCriteria;
import com.furnitureims.repository.ItemModelSummary;
import com.furnitureims.repository.ItemPhotoRepository;
import com.furnitureims.util.ImageResizer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Item model CRUD (FR-ITEM-01, FR-ITEM-03, FR-ITEM-04) and photo management (FR-ITEM-02).
 * Photos live on disk under {@code Photos/<item_model.id>/}, referenced by a relative path
 * in the database - see docs/02-data-model.md section 6.
 */
@Service
public class ItemModelService {

    private static final int MAX_PHOTOS = 5;

    private final ItemModelRepository itemModelRepository;
    private final ItemPhotoRepository itemPhotoRepository;
    private final AppPaths appPaths;
    private final SettingsService settingsService;

    public ItemModelService(ItemModelRepository itemModelRepository, ItemPhotoRepository itemPhotoRepository,
                             AppPaths appPaths, SettingsService settingsService) {
        this.itemModelRepository = itemModelRepository;
        this.itemPhotoRepository = itemPhotoRepository;
        this.appPaths = appPaths;
        this.settingsService = settingsService;
    }

    public Optional<ItemModel> findById(long id) {
        return itemModelRepository.findById(id);
    }

    public List<ItemModelSummary> search(ItemModelSearchCriteria criteria) {
        return itemModelRepository.search(criteria);
    }

    public boolean modelCodeExists(String modelCode) {
        return itemModelRepository.existsByModelCode(modelCode);
    }

    public List<ItemPhoto> photosFor(long itemModelId) {
        return itemPhotoRepository.findByItemModelId(itemModelId);
    }

    public Path photoFile(ItemPhoto photo) {
        return appPaths.photos().resolve(String.valueOf(photo.itemModelId())).resolve(photo.relativePath());
    }

    public long create(ItemModel draft) {
        ItemModel normalized = applyGstSentinelIfDisabled(draft);
        validate(normalized, true);
        return itemModelRepository.create(normalized);
    }

    public void update(ItemModel model) {
        ItemModel normalized = applyGstSentinelIfDisabled(model);
        validate(normalized, false);
        itemModelRepository.update(normalized);
    }

    /** M10: {@code hsn_code}/{@code gst_rate} are {@code NOT NULL} with no default, so a
     *  screen that hides them while GST is off (Workstream D) can't simply leave them blank -
     *  it never fills them in, so whatever it sent here (typically {@code null}/blank) is
     *  replaced with the sentinel instead of being rejected by {@link #validate}. Does
     *  nothing while GST is on, so an existing model's real HSN/rate is never overwritten by
     *  turning the toggle off and back on. */
    private ItemModel applyGstSentinelIfDisabled(ItemModel m) {
        if (settingsService.isGstEnabled()) {
            return m;
        }
        return new ItemModel(m.id(), m.modelCode(), m.modelName(), m.categoryId(), "", BigDecimal.ZERO,
                m.lengthCm(), m.widthCm(), m.heightCm(), m.material(), m.finish(), m.colour(),
                m.defaultSalePrice(), m.active(), m.notes());
    }

    private void validate(ItemModel m, boolean isNew) {
        if (m.modelCode() == null || m.modelCode().isBlank()) {
            throw new IllegalArgumentException("Model code is required.");
        }
        if (m.modelName() == null || m.modelName().isBlank()) {
            throw new IllegalArgumentException("Model name is required.");
        }
        if (settingsService.isGstEnabled()) {
            if (m.hsnCode() == null || m.hsnCode().isBlank()) {
                throw new IllegalArgumentException("HSN code is required.");
            }
            if (m.gstRate() == null) {
                throw new IllegalArgumentException("GST rate is required.");
            }
        }
        if (isNew && itemModelRepository.existsByModelCode(m.modelCode())) {
            throw new IllegalArgumentException("Model code \"" + m.modelCode() + "\" is already in use.");
        }
    }

    public void setActive(long itemModelId, boolean active) {
        itemModelRepository.setActive(itemModelId, active);
    }

    /** FR-ITEM-03: a model with any piece history is never deletable, only discontinued. */
    public void delete(long itemModelId) {
        if (itemModelRepository.hasAnyPieces(itemModelId)) {
            throw new IllegalStateException(
                    "This model has pieces recorded against it and cannot be deleted. Discontinue it instead.");
        }
        for (ItemPhoto photo : itemPhotoRepository.findByItemModelId(itemModelId)) {
            deletePhotoFile(photo);
        }
        itemModelRepository.delete(itemModelId);
    }

    public ItemPhoto addPhoto(long itemModelId, Path sourceFile) {
        List<ItemPhoto> existing = itemPhotoRepository.findByItemModelId(itemModelId);
        if (existing.size() >= MAX_PHOTOS) {
            throw new IllegalStateException("A model can have at most " + MAX_PHOTOS + " photos.");
        }

        Path modelPhotoDir = appPaths.photos().resolve(String.valueOf(itemModelId));
        try {
            Files.createDirectories(modelPhotoDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String extension = sourceFile.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
        String relativePath = UUID.randomUUID() + extension;
        ImageResizer.resizeAndSave(sourceFile, modelPhotoDir.resolve(relativePath));

        boolean primary = existing.isEmpty();
        long photoId = itemPhotoRepository.create(itemModelId, relativePath, existing.size(), primary);
        return new ItemPhoto(photoId, itemModelId, relativePath, existing.size(), primary);
    }

    public void removePhoto(ItemPhoto photo) {
        deletePhotoFile(photo);
        itemPhotoRepository.delete(photo.id());

        if (photo.primary()) {
            List<ItemPhoto> remaining = itemPhotoRepository.findByItemModelId(photo.itemModelId());
            if (!remaining.isEmpty()) {
                itemPhotoRepository.setPrimary(photo.itemModelId(), remaining.get(0).id());
            }
        }
    }

    public void setPrimaryPhoto(ItemPhoto photo) {
        itemPhotoRepository.setPrimary(photo.itemModelId(), photo.id());
    }

    private void deletePhotoFile(ItemPhoto photo) {
        try {
            Files.deleteIfExists(photoFile(photo));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
