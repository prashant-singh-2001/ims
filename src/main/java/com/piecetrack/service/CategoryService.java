package com.piecetrack.service;

import com.piecetrack.domain.Category;
import com.piecetrack.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CategoryService {

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<Category> listActive() {
        return repository.findAllActive();
    }

    public List<Category> listAll() {
        return repository.findAll();
    }

    public Optional<Category> findById(long id) {
        return repository.findById(id);
    }

    public long create(String name) {
        String trimmed = requireNonBlank(name);
        if (repository.existsByName(trimmed)) {
            throw new IllegalArgumentException("A category named \"" + trimmed + "\" already exists.");
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
            throw new IllegalArgumentException("Category name is required.");
        }
        return name.trim();
    }
}
