package com.enterprise.mesh.customer.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.customer.domain.Address;
import com.enterprise.mesh.customer.domain.Customer;
import com.enterprise.mesh.customer.repository.CustomerRepository;

/**
 * Customer profile management.
 *
 * <p>Account creation is keyed on the identity-provider subject rather than on
 * email, so a shopper who changes their email address keeps one account instead
 * of silently acquiring a second one.
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * Returns the caller's profile, creating it on first sign-in.
     *
     * <p>Self-provisioning on first read keeps the signup flow out of the
     * critical path while still guaranteeing a profile exists for every
     * authenticated shopper.
     */
    @Transactional
    public Customer getOrCreate(String subject, String tenantId, String email, String displayName) {
        return customerRepository.findByTenantIdAndSubject(tenantId, subject)
                .orElseGet(() -> create(subject, tenantId, email, displayName));
    }

    private Customer create(String subject, String tenantId, String email, String displayName) {
        Customer customer = new Customer(UUID.randomUUID().toString(), subject, tenantId,
                email, displayName == null || displayName.isBlank() ? email : displayName);
        try {
            Customer saved = customerRepository.save(customer);
            log.info("Provisioned customer {} for subject {}", saved.getId(), subject);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Concurrent first sign-in lost the race; the winner's row is correct.
            log.info("Concurrent provisioning for subject {}; re-reading", subject);
            return customerRepository.findByTenantIdAndSubject(tenantId, subject)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public Customer get(String id, String tenantId) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        if (!customer.getTenantId().equals(tenantId)) {
            // Do not disclose that another tenant's customer id exists.
            throw new CustomerNotFoundException(id);
        }
        return customer;
    }

    @Transactional
    public Customer updateProfile(String id, String tenantId, String displayName, String phone,
                                  String locale, String timezone, Boolean marketingOptIn) {
        Customer customer = get(id, tenantId);
        customer.updateProfile(displayName, phone, locale, timezone, marketingOptIn);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer changeEmail(String id, String tenantId, String newEmail) {
        Customer customer = get(id, tenantId);
        if (customerRepository.existsByTenantIdAndEmailIgnoreCase(tenantId, newEmail)) {
            throw new IllegalArgumentException("Email is already in use");
        }
        customer.changeEmail(newEmail);
        log.info("Customer {} changed email", id);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer addAddress(String id, String tenantId, Address address) {
        Customer customer = get(id, tenantId);
        customer.addAddress(address);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer makeDefaultAddress(String id, String tenantId, Long addressId) {
        Customer customer = get(id, tenantId);
        Address target = customer.getAddresses().stream()
                .filter(address -> addressId.equals(address.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown address " + addressId));
        customer.makeDefault(target);
        return customerRepository.save(customer);
    }

    @Transactional(readOnly = true)
    public List<Address> addresses(String id, String tenantId) {
        return get(id, tenantId).getAddresses();
    }

    /** Soft delete: orders still reference the customer, so the row is retained. */
    @Transactional
    public Customer deactivate(String id, String tenantId) {
        Customer customer = get(id, tenantId);
        customer.deactivate();
        log.info("Deactivated customer {}", id);
        return customerRepository.save(customer);
    }

    /** Thrown when a customer is not visible to the calling tenant. */
    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException(String id) {
            super("Customer " + id + " not found");
        }
    }
}
