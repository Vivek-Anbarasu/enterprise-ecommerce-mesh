package com.enterprise.mesh.inventoryservice.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.inventoryservice.domain.InventoryReservation;
import com.enterprise.mesh.inventoryservice.domain.InventoryReservationLine;
import com.enterprise.mesh.inventoryservice.repository.InventoryReservationLineRepository;
import com.enterprise.mesh.inventoryservice.repository.InventoryReservationRepository;

/**
 * Reclaims stock held by abandoned checkouts.
 *
 * <p>Without this, every abandoned or crashed checkout would permanently reduce
 * availability until a human intervened, which on a busy catalogue means
 * phantom out-of-stock items. The sweeper expires holds past their TTL and
 * returns the units.
 *
 * <p>Runs in every replica; the {@code PENDING} filter plus the row-level
 * {@code @Version} on both tables makes concurrent sweeps converge on the same
 * outcome rather than double-releasing.
 */
@Component
@ConditionalOnProperty(prefix = "mesh.inventory.sweeper", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final InventoryReservationRepository reservationRepository;
    private final InventoryReservationLineRepository lineRepository;
    private final StockMutationService mutationService;
    private final int batchSize;

    public ReservationExpirySweeper(
            InventoryReservationRepository reservationRepository,
            InventoryReservationLineRepository lineRepository,
            StockMutationService mutationService,
            @Value("${mesh.inventory.sweeper.batch-size:200}") int batchSize) {
        this.reservationRepository = reservationRepository;
        this.lineRepository = lineRepository;
        this.mutationService = mutationService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${mesh.inventory.sweeper.interval-ms:60000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sweepExpiredReservations() {
        Instant now = Instant.now();
        List<InventoryReservation> expired = reservationRepository
                .findExpiredPending(now, PageRequest.of(0, batchSize));

        if (expired.isEmpty()) {
            return;
        }
        log.info("Sweeping {} expired reservation(s) at {}", expired.size(), now);

        int released = 0;
        for (InventoryReservation reservation : expired) {
            if (expireOne(reservation.getReservationId())) {
                released++;
            }
        }
        log.info("Sweeper released {} of {} candidate reservation(s)", released, expired.size());
    }

    /**
     * Expires a single reservation in its own transaction.
     *
     * <p>Split out so that one poisoned row cannot roll back the whole batch.
     * Each stock mutation is committed independently, so a crash mid-batch can
     * only lose work that the next sweep will pick up again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(String reservationId) {
        InventoryReservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || !reservation.isPending() || !reservation.isExpired(Instant.now())) {
            return false;
        }

        // Claim the row first: if this succeeds but a stock release below fails,
        // the reservation is EXPIRED and the sweeper will not retry it. Release
        // therefore happens first, while the hold is still PENDING and visible
        // to other sweeps.
        List<InventoryReservationLine> lines =
                lineRepository.findByIdReservationId(reservationId);

        if (lines.isEmpty()) {
            log.error("Reservation {} has no line detail; expiring without releasing stock. "
                    + "Manual reconciliation required.", reservationId);
            reservation.markExpired();
            reservationRepository.save(reservation);
            return false;
        }

        for (InventoryReservationLine line : lines) {
            mutationService.mutate(line.getSku(), line.getQuantity(),
                    reservation.getTenantId(), StockMutationService.Action.RELEASE);
        }

        reservation.markExpired();
        reservationRepository.save(reservation);
        log.info("Expired reservation {} and released {} line(s)",
                reservationId, lines.size());
        return true;
    }
}
