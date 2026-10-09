package com.enterprise.mesh.order.controller;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.mesh.order.api.OrderDtos.OrderResponse;
import com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest;
import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.persistence.OrderRepository;
import com.enterprise.mesh.order.saga.CheckoutSagaOrchestrator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Checkout and order lifecycle")
public class OrderController {

    private final CheckoutSagaOrchestrator sagaOrchestrator;
    private final OrderRepository orderRepository;

    public OrderController(CheckoutSagaOrchestrator sagaOrchestrator,
                           OrderRepository orderRepository) {
        this.sagaOrchestrator = sagaOrchestrator;
        this.orderRepository = orderRepository;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Place an order (idempotent on the Idempotency-Key header)")
    public ResponseEntity<OrderResponse> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        OrderResponse response = sagaOrchestrator.placeOrder(
                request,
                tenantId == null ? "public" : tenantId,
                MDC.get("request_id"));

        // A declined payment or an out-of-stock line is a completed request with
        // an unsuccessful outcome, not a transport failure.
        String status = response.status();
        if (Order.OrderStatus.FAILED.name().equals(status)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Fetch one order")
    public OrderResponse getOrder(
            @PathVariable @Size(max = 36) String orderId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return orderRepository.findById(orderId)
                .filter(order -> tenantId == null
                        || tenantId.equals("public")
                        || order.getTenantId().equals(tenantId))
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "List orders for a customer, newest first")
    public Page<OrderResponse> listOrders(
            @RequestParam(required = false) @Size(max = 64) String customerId,
            @RequestParam(required = false) Order.OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        // Cap page size so a client cannot request the whole table in one call.
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<OrderResponse> results = orderRepository
                .search(tenantId == null ? "public" : tenantId, customerId, status,
                        PageRequest.of(Math.max(page, 0), safeSize,
                                Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(OrderResponse::from);
        return results;
    }

    /** Raised when an order id does not exist for the calling tenant. */
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("Order " + orderId + " not found");
        }
    }
}
