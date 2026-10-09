package com.enterprise.mesh.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 */
@Document(collection = "products")
@CompoundIndex(name = "tenant_active_category", def = "{'tenantId': 1, 'active': 1, 'category': 1}")
public class Product {

    @Id
    private String id;

    @Indexed(unique = true)
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public List<Attribute> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<Attribute> attributes) {
        this.attributes = attributes;
    }

    public List<Money> getPriceList() {
        return priceList;
    }

    public void setPriceList(List<Money> priceList) {
        this.priceList = priceList;
    }

    public Money getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(Money basePrice) {
        this.basePrice = basePrice;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public Double getRatingAverage() {
        return ratingAverage;
    }

    public void setRatingAverage(Double ratingAverage) {
        this.ratingAverage = ratingAverage;
    }

    public Long getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(Long ratingCount) {
        this.ratingCount = ratingCount;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
