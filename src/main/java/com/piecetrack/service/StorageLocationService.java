package com.piecetrack.service;

import com.piecetrack.domain.StorageLocation;
import com.piecetrack.repository.StorageLocationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class StorageLocationService {

    private final StorageLocationRepository repository;

    public StorageLocationService(StorageLocationRepository repository) {
        this.repository = repository;
    }

    public List<StorageLocation> listActive() {
        return repository.findAllActive();
    }

    public List<StorageLocation> listAll() {
        return repository.findAll();
    }

    public Optional<StorageLocation> findById(long id) {
        return repository.findById(id);
    }

    public long create(String name) {
        String trimmed = requireNonBlank(name);
        if (repository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A location named \"" + trimmed + "\" already exists.");
        }
        return repository.create(trimmed);
    }

    public void rename(long id, String newName) {
        repository.rename(id, requireNonBlank(newName));
    }

    public void setActive(long id, boolean active) {
        repository.setActive(id, active);
    }

    private static String requireNonBlank(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Location name is required.");
        }
        return name.trim();
    }
}
