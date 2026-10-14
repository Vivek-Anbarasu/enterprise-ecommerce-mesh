package com.enterprise.mesh.inventoryservice.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.inventoryservice.domain.InventoryItem;
import com.enterprise.mesh.inventoryservice.repository.InventoryItemRepository;

/**
 * Single-transaction stock mutation.
 *
 * <p>Deliberately a separate bean from {@link InventoryService}. Spring's
 * {@code @Transactional} works through a proxy, so a self-invocation such as
 * {@code this.mutate(...)} would silently bypass the proxy and run without a
 * transaction. That matters here: the optimistic-lock retry loop needs each
 * attempt to start from a <em>fresh</em> persistence context, because the
 * context is already poisoned after a locking failure.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockMutationService {

    public enum Action {
        RESERVE, RELEASE, CONFIRM, RECEIVE
    }

    private final InventoryItemRepository itemRepository;
    /**
     * Loads, mutates and flushes one stock row in a single transaction.
     *
     * @throws jakarta.persistence.OptimisticLockException when another
     *         transaction changed the row first; the caller retries
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventoryItem mutate(String sku, int quantity, String tenantId, Action action) {
        InventoryItem item = itemRepository.findBySkuAndTenantId(sku, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown SKU " + sku));

        switch (action) {
            case RESERVE -> item.reserve(quantity);
            case RELEASE -> item.release(quantity);
            case CONFIRM -> item.confirm(quantity);
            case RECEIVE -> item.receive(quantity);
        }

        // saveAndFlush forces the UPDATE (and therefore the version check) to
        // happen here, rather than at some later flush where the exception
        // would surface outside this method's control flow.
        InventoryItem saved = itemRepository.saveAndFlush(item);

        if (action == Action.RESERVE) {
            log.debug("SKU {} reserved {}; available now {}", sku, quantity, saved.available());
        }
        return saved;
    }
}