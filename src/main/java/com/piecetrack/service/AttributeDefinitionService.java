package com.piecetrack.service;

import com.piecetrack.domain.AttributeDefinition;
import com.piecetrack.repository.AttributeDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Mirrors {@link CategoryService}'s shape exactly - a small user-managed lookup list (M15),
 *  reached from the same Categories & Locations screen. */
@Service
public class AttributeDefinitionService {

    private final AttributeDefinitionRepository repository;

    public AttributeDefinitionService(AttributeDefinitionRepository repository) {
        this.repository = repository;
    }

    public List<AttributeDefinition> listActive() {
        return repository.findAllActive();
    }

    public List<AttributeDefinition> listAll() {
        return repository.findAll();
    }

    public long create(String name) {
        String trimmed = requireNonBlank(name);
        if (repository.existsByName(trimmed)) {
            throw new IllegalArgumentException("An attribute named \"" + trimmed + "\" already exists.");
        }
        int nextOrder = repository.findAll().size();
        return repository.create(trimmed, nextOrder);
    }

    public void rename(long id, String newName) {
        repository.rename(id, requireNonBlank(newName));
    }

    public void setActive(long id, boolean active) {
        repository.setActive(id, active);
    }

    private static String requireNonBlank(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Attribute name is required.");
        }
        return name.trim();
    }
}
