package com.furnitureims.service;

import com.furnitureims.domain.Customer;
import com.furnitureims.repository.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Customer capture (FR-SAL-02): a name and phone are the only hard requirements - a
 *  furniture shop's retail customers are mostly unregistered for GST. */
@Service
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    public Optional<Customer> findById(long id) {
        return repository.findById(id);
    }

    public Optional<Customer> findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return repository.findByPhone(phone.trim());
    }

    public List<Customer> search(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return repository.search(text);
    }

    public long create(Customer customer) {
        if (customer.name() == null || customer.name().isBlank()) {
            throw new IllegalArgumentException("Customer name is required.");
        }
        if (customer.phone() == null || customer.phone().isBlank()) {
            throw new IllegalArgumentException("Customer phone is required.");
        }
        return repository.create(customer);
    }
}
