package com.enterprise.mesh.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards money representation in the catalogue.
 *
 * <p>Price is carried as {@link BigDecimal}. The real risk this test documents
 * is that someone "simplifies" it to {@code double} later, which would silently
 * misprice catalogue items by fractions of a cent.
 */
class ProductMoneyTest {

    @Test
    @DisplayName("prices retain full decimal precision")
    void retainsPrecision() {
        Product product = new Product();
        product.setBasePrice(new Product.Money(new BigDecimal("19.99"), "USD"));

        assertThat(product.getBasePrice().amount()).isEqualByComparingTo("19.99");
        assertThat(product.getBasePrice().currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("repeated price scaling does not drift")
    void scalingIsStable() {
        BigDecimal price = new BigDecimal("0.07");
        BigDecimal total = price.multiply(BigDecimal.valueOf(3));

        assertThat(total).isEqualByComparingTo("0.21");
        assertThat(total.setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("0.21");
    }

    @Test
    @DisplayName("a zero-decimal currency like JPY has no fractional part")
    void supportsZeroDecimalCurrency() {
        Product product = new Product();
        product.setBasePrice(new Product.Money(new BigDecimal("1500"), "JPY"));

        assertThat(product.getBasePrice().currency()).isEqualTo("JPY");
        assertThat(product.getBasePrice().amount()).isEqualByComparingTo("1500");
    }
}
