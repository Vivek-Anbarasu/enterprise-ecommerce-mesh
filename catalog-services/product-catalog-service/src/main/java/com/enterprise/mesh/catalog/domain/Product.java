package com.enterprise.mesh.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Catalogue product.
 *
 * <p>MongoDB rather than a relational store because the product shape varies
 * wildly by category (laptops have CPU/RAM, jackets do not) and reads vastly
 * outnumber writes. Prices are held as {@link BigDecimal} and stored as Decimal128
 * to avoid binary floating point drift on money.
 *
 * <p>A plain mutable bean: no invariants, no derived state, no state machine.
 * That is the reason it is the one persistence class in the project where
 * generated accessors are the right trade - {@code Order} and {@code Payment}
 * mutate only through named methods precisely because their transitions are
 * guarded, and bean setters would bypass those guards. Nothing here computes
 * from another field, so live collection access cannot drift the way
 * {@code Order.getLines()} could.
 */
@Document(collection = "products")
@CompoundIndex(name = "tenant_active_category", def = "{'tenantId': 1, 'active': 1, 'category': 1}")
@CompoundIndex(name = "ux_product_tenant_sku", def = "{'tenantId': 1, 'sku': 1}", unique = true)
@Getter
@Setter
public class Product {

    @Id
    private String id;

    /**
     * Unique per tenant, not globally: uniqueness is enforced by
     * {@code ux_product_tenant_sku}. Two tenants are independent catalogues and
     * may both stock {@code SKU-1001}. A bare unique index on this field would
     * make the first tenant to claim a SKU lock every other tenant out of it.
     */
    @Indexed
    private String sku;

    @Indexed
    private String tenantId;

    private String name;

    private String description;

    private String category;

    private String brand;

    /** Free-form category-specific attributes (specs, sizes, colours). */
    private List<Attribute> attributes = new ArrayList<>();

    private List<Money> priceList = new ArrayList<>();

    /** Currency the storefront renders prices in. */
    private Money basePrice;

    private List<String> imageUrls = new ArrayList<>();

    private String thumbnailUrl;

    private Double ratingAverage;

    private Long ratingCount;

    private boolean active = true;

    private Instant createdAt;

    private Instant updatedAt;

    public Product() {
        // required by Mongo
    }

    /** Category-specific attribute. */
    public record Attribute(String name, String value) {
    }

    /** An amount with its currency. */
    public record Money(BigDecimal amount, String currency) {
    }
}
