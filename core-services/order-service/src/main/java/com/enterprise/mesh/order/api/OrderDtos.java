package com.enterprise.mesh.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.domain.OrderLine;

/** Contracts for the checkout API. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * Place an order.
     *
     * @param idempotencyKey required; a retry with the same key returns the
     *                       original order instead of charging twice
     * @param paymentToken   opaque token from the payment provider's SDK. Raw
     *                       card data must never reach this service (PCI DSS).
     */
    public record PlaceOrderRequest(
            @NotBlank @Size(max = 64) String idempotencyKey,
            @NotBlank @Size(max = 64) String customerId,
            @NotEmpty @Valid List<LineRequest> lines,
            @NotBlank @Size(max = 1024) String shippingAddress,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 4) BigDecimal taxAmount,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 4) BigDecimal shippingAmount,
            @NotBlank @Size(max = 128) String paymentToken,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code")
            String currency) {
    }

    public record LineRequest(
            @NotBlank @Size(max = 64) String sku,
            @NotBlank @Size(max = 256) String productName,
            @Min(1) int quantity,
            @NotNull @DecimalMin(value = "0.00")
            @Digits(integer = 15, fraction = 4) BigDecimal unitPrice) {
    }

    public record LineResponse(
            String sku,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {
    }

    public record OrderResponse(
            String id,
            String idempotencyKey,
            String customerId,
            String status,
            BigDecimal totalAmount,
            String currency,
            BigDecimal taxAmount,
            BigDecimal shippingAmount,
            String paymentReference,
            String inventoryReservationId,
            String failureReason,
            List<LineResponse> lines,
            Instant createdAt,
            Instant updatedAt) {

        public static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getIdempotencyKey(),
                    order.getCustomerId(),
                    order.getStatus().name(),
                    order.getTotalAmount(),
                    order.getCurrency(),
                    order.getTaxAmount(),
                    order.getShippingAmount(),
                    order.getPaymentReference(),
                    order.getInventoryReservationId(),
                    order.getFailureReason(),
                    order.getLines().stream()
                            .map(line -> new LineResponse(
                                    line.getSku(),
                                    line.getProductName(),
                                    line.getQuantity(),
                                    line.getUnitPrice(),
                                    line.getLineTotal()))
                            .toList(),
                    order.getCreatedAt(),
                    order.getUpdatedAt());
        }
    }

    /** Published when an order is created; consumed by notification and shipping. */
    public record OrderCreatedEvent(
            String orderId,
            String customerId,
            String reservationId,
            BigDecimal totalAmount,
            String currency,
            List<String> skus,
            Instant occurredAt) {
    }

    public record OrderStatusEvent(
            String orderId,
            String customerId,
            String previousStatus,
            String status,
            String reason,
            Instant occurredAt) {
    }

    public static LineResponse toResponse(OrderLine line) {
        return new LineResponse(line.getSku(), line.getProductName(), line.getQuantity(),
                line.getUnitPrice(), line.getLineTotal());
    }
}
