package com.enterprise.mesh.customer.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A shopper account.
 *
 * <p>This table holds PII, so it is the most likely target of a breach. No
 * password or credential is ever stored: the {@code subject} claim from the
 * external identity provider is the only credential the mesh trusts.
 */
@Entity
@Table(name = "customers", indexes = {
        @Index(name = "idx_customer_tenant_email", columnList = "tenant_id,email"),
        @Index(name = "idx_customer_subject", columnList = "tenant_id,subject")
})
public class Customer {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    /** Identity-provider subject; the join key to the external IdP. */
    @Column(name = "subject", nullable = false, length = 128)
    private String subject;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "phone", length = 32)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CustomerStatus status;

    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn;

    @Column(name = "locale", length = 16)
    private String locale;

    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Address> addresses = new ArrayList<>();

    protected Customer() {
        // required by JPA
    }

    public Customer(String id, String subject, String tenantId, String email, String displayName) {
        this.id = id;
        this.subject = subject;
        this.tenantId = tenantId;
        this.email = email;
        this.displayName = displayName;
        this.status = CustomerStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Null arguments leave the existing value unchanged (PATCH semantics). */
    public void updateProfile(String displayName, String phone, String locale,
                              String timezone, Boolean marketingOptIn) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (locale != null) {
            this.locale = locale;
        }
        if (timezone != null) {
            this.timezone = timezone;
        }
        if (marketingOptIn != null) {
            this.marketingOptIn = marketingOptIn;
        }
        this.updatedAt = Instant.now();
    }

    public void changeEmail(String email) {
        this.email = email;
        this.updatedAt = Instant.now();
    }

    public void addAddress(Address address) {
        // A brand-new account should already have somewhere to ship to.
        if (addresses.isEmpty()) {
            address.markAsDefault();
        }
        address.attachTo(this);
        this.addresses.add(address);
        this.updatedAt = Instant.now();
    }

    /** Promotes one address to default and demotes the previous one. */
    public void makeDefault(Address address) {
        for (Address existing : addresses) {
            if (existing.isDefault()) {
                existing.clearDefault();
            }
        }
        address.markAsDefault();
        this.updatedAt = Instant.now();
    }

    public void removeAddress(Address address) {
        boolean wasDefault = address.isDefault();
        addresses.remove(address);
        if (wasDefault && !addresses.isEmpty()) {
            addresses.get(0).markAsDefault();
        }
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.status = CustomerStatus.DEACTIVATED;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPhone() {
        return phone;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public boolean isMarketingOptIn() {
        return marketingOptIn;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<Address> getAddresses() {
        return addresses;
    }
}
