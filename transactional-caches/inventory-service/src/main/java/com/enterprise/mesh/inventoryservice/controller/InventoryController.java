package com.enterprise.mesh.inventoryservice.controller;
import lombok.RequiredArgsConstructor;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReservationStatusResponse;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReserveStockRequest;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReserveStockResponse;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.StockLevelResponse;
import com.enterprise.mesh.inventoryservice.service.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Inventory API.
 *
 * <p>Reserve/confirm/release are intended for the order saga and are called
 * service-to-service; read endpoints are safe for the storefront.
 */
@RestController
@RequestMapping("/api/inventory")
@Validated
@Tag(name = "Inventory", description = "Stock levels and checkout reservations")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    @GetMapping("/{sku}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Current sellable availability for a SKU")
    public StockLevelResponse stockLevel(
            @PathVariable @NotBlank @Size(max = 64) String sku,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return inventoryService.stockLevel(sku, tenantId == null ? "public" : tenantId);
    }

    @PostMapping("/reservations")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Hold stock for a checkout attempt (idempotent on reservationId)")
    public ResponseEntity<ReserveStockResponse> reserve(
            @Valid @RequestBody ReserveStockRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        ReserveStockResponse response =
                inventoryService.reserve(request, tenantId == null ? "public" : tenantId);
        // 200 rather than 201: the same reservationId replays the same hold, so
        // this endpoint is idempotent rather than resource-creating.
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reservations/{reservationId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Reservation state")
    public ReservationStatusResponse reservationStatus(
            @PathVariable @Size(max = 64) String reservationId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return inventoryService.status(reservationId, tenantId == null ? "public" : tenantId);
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Confirm a reservation after payment succeeded (idempotent)")
    public ResponseEntity<ReservationStatusResponse> confirm(
            @PathVariable @Size(max = 64) String reservationId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(
                inventoryService.confirm(reservationId, tenantId == null ? "public" : tenantId));
    }

    @PostMapping("/reservations/{reservationId}/release")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Release an unconfirmed reservation (idempotent)")
    public ResponseEntity<ReservationStatusResponse> release(
            @PathVariable @Size(max = 64) String reservationId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(
                inventoryService.release(reservationId, tenantId == null ? "public" : tenantId));
    }

    @PostMapping("/reservations/{reservationId}/unconfirm")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Undo a confirmed reservation whose order was never persisted "
            + "(idempotent); returns the units to sale")
    public ResponseEntity<ReservationStatusResponse> unconfirm(
            @PathVariable @Size(max = 64) String reservationId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(
                inventoryService.unconfirm(reservationId, tenantId == null ? "public" : tenantId));
    }

    @PostMapping("/{sku}/receipts")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record goods received against a SKU")
    public ResponseEntity<StockLevelResponse> receive(
            @PathVariable @Size(max = 64) String sku,
            @RequestParam @Min(1) @Max(100_000) int quantity,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(inventoryService.receive(sku, quantity, tenantId == null ? "public" : tenantId));
    }
}