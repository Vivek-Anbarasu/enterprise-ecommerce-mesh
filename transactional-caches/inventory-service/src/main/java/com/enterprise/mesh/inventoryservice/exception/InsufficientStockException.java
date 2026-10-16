package com.enterprise.mesh.inventoryservice.exception;

import lombok.Getter;

/**
 * Raised when a SKU has insufficient sellable availability. Maps to HTTP 409.
 *
 * <p>Carries structured data rather than leaving it in the message so
 * {@code InventoryExceptionHandler} can put {@code sku}, {@code requested} and
 * {@code available} into the RFC 9457 problem document as typed properties,
 * instead of parsing them back out of a string.
 *
 * <p>All fields are final, so {@code @Getter} cannot generate a setter and there
 * is no encapsulation decision to make here.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Insufficient stock for SKU " + sku + ": requested " + requested
                + " but only " + available + " available");
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }
}