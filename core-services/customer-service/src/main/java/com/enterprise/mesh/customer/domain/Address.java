package com.enterprise.mesh.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A delivery address in a customer's address book. */
@Entity
@Table(name = "customer_addresses")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "label", length = 64)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 128)
    private String recipientName;

    @Column(name = "line1", nullable = false, length = 256)
    private String line1;

    @Column(name = "line2", length = 256)
    private String line2;

    @Column(name = "city", nullable = false, length = 128)
    private String city;

    @Column(name = "state", length = 128)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 32)
    private String postalCode;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    protected Address() {
        // required by JPA
    }

    public Address(String recipientName, String line1, String line2, String city,
                   String state, String postalCode, String country) {
        if (country == null || country.length() != 2) {
            throw new IllegalArgumentException("Country must be a 2-letter ISO 3166-1 code");
        }
        this.recipientName = recipientName;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country.toUpperCase();
    }

    void attachTo(Customer customer) {
        this.customer = customer;
    }

    void markAsDefault() {
        this.defaultAddress = true;
    }

    void clearDefault() {
        this.defaultAddress = false;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public boolean isDefault() {
        return defaultAddress;
    }

    /** Single-line form used when passing an address to the order service. */
    public String toSingleLine() {
        StringBuilder builder = new StringBuilder();
        builder.append(recipientName).append(", ").append(line1);
        if (line2 != null && !line2.isBlank()) {
            builder.append(", ").append(line2);
        }
        builder.append(", ").append(city);
        if (state != null && !state.isBlank()) {
            builder.append(", ").append(state);
        }
        return builder.append(" ").append(postalCode).append(", ").append(country).toString();
    }
}
