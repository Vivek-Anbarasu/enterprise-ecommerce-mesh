package com.enterprise.mesh.customer.domain;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the encapsulation of {@link Customer#getAddresses()}.
 *
 * <p>Plain POJO tests - no Spring context, no database. The aggregate is
 * small enough that its invariants are worth asserting directly rather than
 * only through HTTP.
 */
class CustomerTest {

    private static Customer customer() {
        return new Customer("cust-1", "subject-1", "acme", "sam@example.com", "Sam");
    }

    private static Address address(String line1) {
        return new Address("Sam", line1, null, "Leeds", null, "LS1 1AA", "GB");
    }

    @Test
    @DisplayName("getAddresses() hands out an unmodifiable view")
    void addressListIsUnmodifiable() {
        Customer customer = customer();
        customer.addAddress(address("1 Test Street"));

        List<Address> addresses = customer.getAddresses();

        // The collection is cascade=ALL, orphanRemoval=true, so a caller that
        // could clear it would have the rows deleted on the next flush.
        assertThatThrownBy(addresses::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> addresses.add(address("2 Sneaky Street")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> addresses.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(addresses).hasSize(1);
    }

    @Test
    @DisplayName("getAddresses() is a live view, not a snapshot")
    void addressListReflectsLaterMutations() {
        Customer customer = customer();
        List<Address> addresses = customer.getAddresses();
        assertThat(addresses).isEmpty();

        customer.addAddress(address("1 Test Street"));

        // Unmodifiable must not mean detached: addAddress is still the only way
        // to change the book, and callers holding the list must see it.
        assertThat(addresses).hasSize(1);
        assertThat(customer.getAddresses()).hasSize(1);
    }

    @Test
    @DisplayName("the first address becomes the default")
    void firstAddressIsDefault() {
        Customer customer = customer();
        customer.addAddress(address("1 Test Street"));
        customer.addAddress(address("2 Test Street"));

        assertThat(customer.getAddresses()).hasSize(2);
        assertThat(customer.getAddresses().get(0).isDefault()).isTrue();
        assertThat(customer.getAddresses().get(1).isDefault()).isFalse();
    }

    @Test
    @DisplayName("makeDefault demotes the previous default")
    void makeDefaultIsExclusive() {
        Customer customer = customer();
        Address first = address("1 Test Street");
        Address second = address("2 Test Street");
        customer.addAddress(first);
        customer.addAddress(second);

        customer.makeDefault(second);

        assertThat(first.isDefault()).isFalse();
        assertThat(second.isDefault()).isTrue();
    }

    @Test
    @DisplayName("removing the default promotes another address")
    void removingDefaultPromotesAnother() {
        Customer customer = customer();
        Address first = address("1 Test Street");
        Address second = address("2 Test Street");
        customer.addAddress(first);
        customer.addAddress(second);

        customer.removeAddress(first);

        assertThat(customer.getAddresses()).hasSize(1);
        assertThat(second.isDefault()).isTrue();
    }

    @Test
    @DisplayName("address label is the only mutable field")
    void onlyLabelIsMutable() {
        Address address = address("1 Test Street");
        address.setLabel("Home");

        assertThat(address.getLabel()).isEqualTo("Home");
        assertThat(address.toSingleLine()).isEqualTo("Sam, 1 Test Street, Leeds LS1 1AA, GB");
    }
}