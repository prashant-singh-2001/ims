package com.furnitureims.service;

import com.furnitureims.domain.ShopProfile;
import com.furnitureims.domain.Supplier;
import com.furnitureims.money.Money;
import com.furnitureims.repository.ShopProfileRepository;
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
    private final ShopProfileRepository shopProfileRepository;
    private final SettingsService settingsService;

    public SupplierService(SupplierRepository repository, PaymentService paymentService,
                            ShopProfileRepository shopProfileRepository, SettingsService settingsService) {
        this.repository = repository;
        this.paymentService = paymentService;
        this.shopProfileRepository = shopProfileRepository;
        this.settingsService = settingsService;
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
        Supplier normalized = applyStateSentinelIfNeeded(supplier);
        validate(normalized);
        return repository.create(normalized);
    }

    public void update(Supplier supplier) {
        Supplier normalized = applyStateSentinelIfNeeded(supplier);
        validate(normalized);
        repository.update(normalized);
    }

    /** M10: {@code supplier.state_code} is {@code NOT NULL} with no default. GST is what
     *  makes it a hard requirement (it decides CGST/SGST vs IGST) - a shop that doesn't
     *  track GST may simply not know or care about a supplier's state, so a blank one is
     *  filled with the shop's own state instead of blocking the save. Never overwrites a
     *  state the owner actually entered - the field stays on the supplier editor regardless
     *  of the toggle (it is ordinary address data, not GST-only like the GSTIN field). */
    private Supplier applyStateSentinelIfNeeded(Supplier s) {
        if (settingsService.isGstEnabled() || (s.stateCode() != null && !s.stateCode().isBlank())) {
            return s;
        }
        ShopProfile shop = shopProfileRepository.find()
                .orElseThrow(() -> new IllegalStateException("Shop profile has not been set up."));
        return new Supplier(s.id(), s.name(), s.gstin(), s.addressLine1(), s.addressLine2(), s.city(), s.pincode(),
                shop.stateName(), shop.stateCode(), s.phone(), s.email(), s.contactPerson(), s.openingBalance(),
                s.active(), s.notes());
    }

    private void validate(Supplier s) {
        if (s.name() == null || s.name().isBlank()) {
            throw new IllegalArgumentException("Supplier name is required.");
        }
        if (settingsService.isGstEnabled() && (s.stateCode() == null || s.stateCode().isBlank())) {
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
