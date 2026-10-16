package com.enterprise.mesh.inventoryservice.exception;

import com.enterprise.mesh.inventoryservice.domain.InventoryItem;

/**
 * Raised when two transactions raced for the same stock row and the loser found
 * the row had changed. Callers should retry a bounded number of times before
 * surfacing a conflict.
 */
public class ConcurrentStockUpdateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentStockUpdateException(String sku, Throwable cause) {
        super("Concurrent stock update detected for SKU " + sku + "; please retry", cause);
    }

    public static ConcurrentStockUpdateException forItem(InventoryItem item, Throwable cause) {
        return new ConcurrentStockUpdateException(item.getSku(), cause);
    }
}
