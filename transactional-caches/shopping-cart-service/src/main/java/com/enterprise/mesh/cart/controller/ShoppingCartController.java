package com.enterprise.mesh.cart.controller;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.enterprise.mesh.cart.service.ShoppingCartService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
@Tag(name = "Shopping Cart", description = "Session cart operations")
@RequiredArgsConstructor
public class ShoppingCartController {

    private final ShoppingCartService cartService;
    public record AddItemRequest(
            @NotBlank @Size(max = 64) String sku,
            @NotBlank @Size(max = 256) String name,
            @Min(1) int quantity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @Size(max = 3) String currency) {
    }

    public record UpdateQuantityRequest(@Min(0) int quantity) {
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Fetch the caller's cart")
    public ShoppingCartService.CartSnapshot getCart(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return cartService.getCart(tenant(tenantId), subject(jwt));
    }

    @PostMapping("/items")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Add an item, or increment an existing SKU's quantity")
    public ShoppingCartService.CartSnapshot addItem(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @Valid @RequestBody AddItemRequest request) {
        return cartService.addItem(tenant(tenantId), subject(jwt), request.sku(), request.name(),
                request.quantity(), request.unitPrice(),
                request.currency() == null ? "USD" : request.currency());
    }

    @PutMapping("/items/{sku}")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Set an absolute quantity; zero removes the line")
    public ShoppingCartService.CartSnapshot updateQuantity(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @PathVariable @Size(max = 64) String sku,
            @Valid @RequestBody UpdateQuantityRequest request) {
        return cartService.updateQuantity(tenant(tenantId), subject(jwt), sku, request.quantity());
    }

    @DeleteMapping("/items/{sku}")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Remove a line")
    public ShoppingCartService.CartSnapshot removeItem(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @PathVariable @Size(max = 64) String sku) {
        return cartService.removeItem(tenant(tenantId), subject(jwt), sku);
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Empty the cart, e.g. after checkout")
    public ResponseEntity<Void> clearCart(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        cartService.clearCart(tenant(tenantId), subject(jwt));
        return ResponseEntity.noContent().build();
    }

    /**
     * The cart is always scoped to the authenticated subject, never to a
     * client-supplied customer id - otherwise any shopper could read or modify
     * another shopper's basket by changing an identifier.
     */
    private String subject(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new IllegalArgumentException("Authenticated subject is required");
        }
        return jwt.getSubject();
    }

    private String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "public" : tenantId;
    }
}