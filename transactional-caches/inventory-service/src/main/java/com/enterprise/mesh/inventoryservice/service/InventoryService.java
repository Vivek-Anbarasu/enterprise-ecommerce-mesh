package com.enterprise.mesh.inventoryservice.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.inventoryservice.api.InventoryDtos.Line;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReservationLineResult;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReservationStatusResponse;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReserveStockRequest;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.ReserveStockResponse;
import com.enterprise.mesh.inventoryservice.api.InventoryDtos.StockLevelResponse;
import com.enterprise.mesh.inventoryservice.domain.InventoryItem;
import com.enterprise.mesh.inventoryservice.domain.InventoryReservation;
import com.enterprise.mesh.inventoryservice.domain.InventoryReservationLine;
import com.enterprise.mesh.inventoryservice.exception.InsufficientStockException;
import com.enterprise.mesh.inventoryservice.repository.InventoryItemRepository;
import com.enterprise.mesh.inventoryservice.repository.InventoryReservationLineRepository;
import com.enterprise.mesh.inventoryservice.repository.InventoryReservationRepository;

/**
 * Stock reservation lifecycle: reserve -> confirm (paid) or release (abandoned).
 *
 * <p>This service is a saga participant. It never calls other services; it
 * accepts a decision, applies it locally, and reports the outcome. Reservations
 * are all-or-nothing: holding some lines but not others would strand stock that
 * nothing is able to release.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {

    /**
     * Optimistic-lock retries. Two shoppers racing for the last unit on a
     * popular SKU is a normal occurrence, not an exceptional one, so a small
     * retry budget is applied before surfacing a conflict to the caller.
     */
    private static final int MAX_LOCK_RETRIES = 5;

    private final InventoryItemRepository itemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationLineRepository lineRepository;
    private final StockMutationService mutationService;
    /**
     * Holds stock for every requested line, or holds nothing.
     *
     * <p>Idempotent on {@code reservationId}: replaying the request returns the
     * existing reservation instead of double-holding stock.
     */
    @Transactional
    public ReserveStockResponse reserve(ReserveStockRequest request, String tenantId) {
        var existing = reservationRepository
                .findByReservationIdAndTenantId(request.reservationId(), tenantId);
        if (existing.isPresent()) {
            log.info("Reservation {} already exists with status {}; returning current state",
                    request.reservationId(), existing.get().getStatus());
            return toResponse(existing.get());
        }

        if (request.expiryTooFar()) {
            throw new IllegalArgumentException("Reservation expiry may not exceed "
                    + com.enterprise.mesh.inventoryservice.api.InventoryDtos.MAX_TTL_SECONDS + " seconds");
        }

        // Reject duplicate SKUs up front; the composite key would otherwise
        // produce a confusing constraint violation deep in the flush.
        List<String> skus = request.lines().stream().map(Line::sku).toList();
        if (skus.size() != skus.stream().distinct().count()) {
            throw new IllegalArgumentException("Duplicate SKU in reservation lines: " + skus);
        }

        List<ReservationLineResult> results = new ArrayList<>();
        for (Line line : request.lines()) {
            results.add(reserveLine(line.sku(), line.quantity(), tenantId));
        }

        if (results.stream().anyMatch(result -> !result.reserved())) {
            ReservationLineResult failure = results.stream()
                    .filter(result -> !result.reserved())
                    .findFirst()
                    .orElseThrow();

            // Compensate: return whatever we managed to hold, so a failed
            // reservation never leaves stock stranded.
            int compensated = 0;
            for (ReservationLineResult result : results) {
                if (result.reserved()) {
                    tryRelease(result.sku(), result.quantity(), tenantId);
                    compensated++;
                }
            }
            log.warn("Reservation {} failed on SKU {}; compensated {} held line(s)",
                    request.reservationId(), failure.sku(), compensated);

            throw new InsufficientStockException(failure.sku(), failure.quantity(), 0);
        }

        int totalQuantity = request.lines().stream().mapToInt(Line::quantity).sum();
        InventoryReservation reservation = new InventoryReservation(
                request.reservationId(),
                request.lines().get(0).sku(),
                tenantId,
                request.orderId(),
                totalQuantity,
                request.expiresAt());
        reservationRepository.save(reservation);

        // Persist per-SKU lines so confirm/release act on the right SKUs later.
        for (Line line : request.lines()) {
            lineRepository.save(new InventoryReservationLine(
                    request.reservationId(), line.sku(), line.quantity()));
        }

        log.info("Reserved {} unit(s) across {} line(s) for order {} (reservation {})",
                totalQuantity, request.lines().size(), request.orderId(), request.reservationId());

        return new ReserveStockResponse(reservation.getReservationId(), request.orderId(),
                true, results, request.expiresAt());
    }

    /**
     * Confirms a reservation after successful payment, permanently decrementing
     * stock. Idempotent: confirming twice is a no-op, not an error, because the
     * saga may legitimately redeliver the decision.
     */
    @Transactional
    public ReservationStatusResponse confirm(String reservationId, String tenantId) {
        InventoryReservation reservation = loadReservation(reservationId, tenantId);

        if (reservation.confirm()) {
            reservationRepository.save(reservation);
            forEachLine(reservationId, (sku, quantity) ->
                    mutateWithRetry(sku, quantity, tenantId, StockMutationService.Action.CONFIRM));
            log.info("Confirmed reservation {} for order {}", reservationId, reservation.getOrderId());
        } else {
            log.info("Reservation {} already {}; confirm is a no-op",
                    reservationId, reservation.getStatus());
        }

        return toStatusResponse(reservation);
    }

    /**
     * Releases an unconfirmed reservation, returning stock to availability.
     * Idempotent, so a duplicated saga compensation is harmless.
     */
    @Transactional
    public ReservationStatusResponse release(String reservationId, String tenantId) {
        InventoryReservation reservation = loadReservation(reservationId, tenantId);

        if (reservation.release()) {
            reservationRepository.save(reservation);
            forEachLine(reservationId, (sku, quantity) ->
                    mutateWithRetry(sku, quantity, tenantId, StockMutationService.Action.RELEASE));
            log.info("Released reservation {} for order {}", reservationId, reservation.getOrderId());
        } else {
            log.info("Reservation {} already {}; release is a no-op",
                    reservationId, reservation.getStatus());
        }

        return toStatusResponse(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationStatusResponse status(String reservationId, String tenantId) {
        return toStatusResponse(loadReservation(reservationId, tenantId));
    }

    @Transactional(readOnly = true)
    public StockLevelResponse stockLevel(String sku, String tenantId) {
        InventoryItem item = itemRepository.findBySkuAndTenantId(sku, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown SKU " + sku));
        return newStockLevel(item);
    }

    /** Bulk goods-in; also used by warehouse receiving and replenishment. */
    @Transactional
    public StockLevelResponse receive(String sku, int quantity, String tenantId) {
        return newStockLevel(mutateWithRetry(sku, quantity, tenantId, StockMutationService.Action.RECEIVE));
    }

    // ---------------------------------------------------------------- internals

    @FunctionalInterface
    private interface LineAction {
        void accept(String sku, int quantity);
    }

    private ReservationLineResult reserveLine(String sku, int quantity, String tenantId) {
        try {
            mutateWithRetry(sku, quantity, tenantId, StockMutationService.Action.RESERVE);
            return new ReservationLineResult(sku, quantity, true, null);
        } catch (InsufficientStockException e) {
            return new ReservationLineResult(sku, quantity, false, e.getMessage());
        }
    }

    private void tryRelease(String sku, int quantity, String tenantId) {
        try {
            mutateWithRetry(sku, quantity, tenantId, StockMutationService.Action.RELEASE);
        } catch (RuntimeException e) {
            // Log loudly but do not mask the original failure: the sweeper job
            // will retry this hold when its expiry passes.
            log.error("Compensating release failed for SKU {} qty {}: {}", sku, quantity, e.toString());
        }
    }

    /**
     * Applies one stock mutation, retrying a bounded number of times on an
     * optimistic-lock conflict.
     *
     * <p>Each attempt runs in its own transaction (see {@link StockMutationService})
     * because after a locking failure the persistence context is unusable.
     */
    private InventoryItem mutateWithRetry(String sku, int quantity, String tenantId,
                                          StockMutationService.Action action) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_LOCK_RETRIES; attempt++) {
            try {
                return mutationService.mutate(sku, quantity, tenantId, action);
            } catch (OptimisticLockingFailureException
                     | jakarta.persistence.OptimisticLockException e) {
                lastFailure = e;
                log.warn("Optimistic lock conflict on SKU {} (attempt {}/{})",
                        sku, attempt, MAX_LOCK_RETRIES);
            }
        }
        throw new com.enterprise.mesh.inventoryservice.exception.ConcurrentStockUpdateException(
                sku, lastFailure);
    }

    /** Reads the persisted per-SKU lines for a reservation. */
    private void forEachLine(String reservationId, LineAction action) {
        List<InventoryReservationLine> lines = lineRepository.findByIdReservationId(reservationId);
        if (lines.isEmpty()) {
            // Reservations created before line tracking existed have only the
            // aggregate row; fall back to it rather than silently doing nothing.
            log.warn("No line detail for reservation {}; falling back to aggregate row", reservationId);
            throw new IllegalStateException(
                    "Missing line detail for reservation " + reservationId);
        }
        lines.forEach(line -> action.accept(line.getSku(), line.getQuantity()));
    }

    private InventoryReservation loadReservation(String reservationId, String tenantId) {
        return reservationRepository.findByReservationIdAndTenantId(reservationId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reservation " + reservationId));
    }

    private ReserveStockResponse toResponse(InventoryReservation reservation) {
        List<ReservationLineResult> lines = lineRepository
                .findByIdReservationId(reservation.getReservationId()).stream()
                .map(line -> new ReservationLineResult(
                        line.getSku(), line.getQuantity(), true, null))
                .toList();

        return new ReserveStockResponse(reservation.getReservationId(), reservation.getOrderId(),
                reservation.isPending(), lines, reservation.getExpiresAt());
    }

    private ReservationStatusResponse toStatusResponse(InventoryReservation reservation) {
        return new ReservationStatusResponse(reservation.getReservationId(), reservation.getOrderId(),
                reservation.getStatus().name(), reservation.getQuantity(), reservation.getExpiresAt());
    }

    private StockLevelResponse newStockLevel(InventoryItem item) {
        return new StockLevelResponse(item.getSku(), item.getLocationCode(), item.getOnHand(),
                item.getReserved(), item.available(), item.getUpdatedAt());
    }
}