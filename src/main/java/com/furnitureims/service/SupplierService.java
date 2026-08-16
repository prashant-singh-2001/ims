package com.furnitureims.service;

import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.SupplierRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Supplier CRUD (FR-PUR-01) and dues (FR-PUR-09). GSTIN format/state-prefix validation
 * (FR-PUR-02) is deliberately not enforced here - the FR is explicit that a mismatch is a
 * non-blocking UI warning, unlike the shop's own GSTIN in the setup wizard, so that check
 * lives in the supplier editor controller instead.
 */
@Service
public class SupplierService {

    private final SupplierRepository repository;
    private final PaymentService paymentService;

    public SupplierService(SupplierRepository repository, PaymentService paymentService) {
        this.repository = repository;
        this.paymentService = paymentService;
    }

    public List<Supplier> listActive() {
        return repository.findAllActive();
    }

    public List<Supplier> listAll() {
        return repository.findAll();
    }

    public Optional<Supplier> findById(long id) {
        return repository.findById(id);
    }

    public long create(Supplier supplier) {
        validate(supplier);
        return repository.create(supplier);
    }

    public void update(Supplier supplier) {
        validate(supplier);
        repository.update(supplier);
    }

    private void validate(Supplier s) {
        if (s.name() == null || s.name().isBlank()) {
            throw new IllegalArgumentException("Supplier name is required.");
        }
        if (s.stateCode() == null || s.stateCode().isBlank()) {
            throw new IllegalArgumentException("Supplier state is required - it decides CGST/SGST vs IGST.");
        }
    }

    public void setActive(long id, boolean active) {
        repository.setActive(id, active);
    }

    /** Opening balance + the balance of every received bill (FR-PUR-09), via
     *  {@link PaymentService} - see docs/02-data-model.md section 4.6 for the full formula. */
    public Money dues(long supplierId) {
        return paymentService.supplierDues(supplierId);
    }
}
