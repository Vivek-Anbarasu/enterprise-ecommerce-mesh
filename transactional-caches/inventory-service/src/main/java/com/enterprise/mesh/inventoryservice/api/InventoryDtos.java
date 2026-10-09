package com.enterprise.mesh.inventoryservice.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request/response contracts for the inventory API. */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /** Upper bound on how long a hold may live, guarding against a stuck hold. */
    public static final long MAX_TTL_SECONDS = 3600;

    /**
     * Request to hold stock for a checkout attempt.
     *
     * @param reservationId client-generated idempotency key; replaying the same
     *                      id must not double-reserve
     */
    public record ReserveStockRequest(
            @NotBlank @Size(max = 64) String reservationId,
            @NotBlank @Size(max = 64) String orderId,
            @NotEmpty @Valid List<Line> lines,
            @NotNull Instant expiresAt) {

        /** True when the requested expiry is unreasonably far out. */
        public boolean expiryTooFar() {
            return expiresAt.isAfter(Instant.now().plusSeconds(MAX_TTL_SECONDS));
        }
    }

    public record Line(
            @NotBlank @Size(max = 64) String sku,
            @Min(1) @Max(10_000) int quantity) {
    }

    public record StockLevelResponse(
            String sku,
            String locationCode,
            int onHand,
            int reserved,
            int available,
            Instant asOf) {
    }

    /** Per-SKU outcome; a partially failed reservation is reported explicitly. */
    public record ReservationLineResult(String sku, int quantity, boolean reserved, String reason) {
    }

    public record ReserveStockResponse(
            String reservationId,
            String orderId,
            boolean success,
            List<ReservationLineResult> lines,
            Instant expiresAt) {
    }

    public record ReservationStatusResponse(
            String reservationId,
            String orderId,
            String status,
            int quantity,
            Instant expiresAt) {
    }
}
