package com.enterprise.mesh.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Guards catalogue product invariants. */
class ProductTest {

    @Test
    @DisplayName("a new product is active by default")
    void defaultsToActive() {
        assertThat(new Product().isActive()).isTrue();
    }

    @Test
    @DisplayName("category-specific attributes are preserved verbatim")
    void retainsAttributes() {
        Product product = new Product();
        product.setAttributes(java.util.List.of(
                new Product.Attribute("CPU", "Apple M3 Pro"),
                new Product.Attribute("RAM", "18GB")));

        assertThat(product.getAttributes()).hasSize(2);
        assertThat(product.getAttributes().get(0).name()).isEqualTo("CPU");
    }

    @Test
    @DisplayName("deactivating preserves the SKU so order history stays resolvable")
    void deactivationKeepsSku() {
        Product product = new Product();
        product.setSku("SKU-1");
        product.setActive(false);

        assertThat(product.isActive()).isFalse();
        assertThat(product.getSku()).isEqualTo("SKU-1");
    }
}
