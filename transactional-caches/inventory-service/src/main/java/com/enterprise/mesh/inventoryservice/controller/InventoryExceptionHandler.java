package com.enterprise.mesh.inventoryservice.controller;

import java.net.URI;
import java.util.Map;

import com.enterprise.mesh.inventoryservice.exception.ConcurrentStockUpdateException;
import com.enterprise.mesh.inventoryservice.exception.InsufficientStockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain failures into RFC 9457 problem documents with meaningful
 * status codes.
 *
 * <p>The distinction that matters commercially: insufficient stock is a
 * <em>409 Conflict</em> that the shopper can act on (pick another item), while a
 * concurrent-update failure is a <em>503</em> that a retry may resolve. Returning
 * 500 for both would make the storefront show a generic error for an ordinary
 * "item sold out while you were deciding" situation.
 */
@RestControllerAdvice
public class InventoryExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryExceptionHandler.class);
    private static final URI PROBLEM_BASE = URI.create("https://errors.enterprise-ecommerce.com/inventory/");

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        log.info("Out of stock: sku={} requested={} available={}",
                ex.getSku(), ex.getRequested(), ex.getAvailable());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Insufficient Stock");
        problem.setType(URI.create(PROBLEM_BASE + "insufficient-stock"));
        problem.setProperties(Map.of(
                "sku", ex.getSku(),
                "requested", ex.getRequested(),
                "available", ex.getAvailable()));
        return problem;
    }

    @ExceptionHandler({ConcurrentStockUpdateException.class, OptimisticLockingFailureException.class})
    public ProblemDetail handleConflict(Exception ex) {
        log.warn("Stock update contention: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Stock is being updated by other shoppers. Please retry.");
        problem.setTitle("Concurrent Update");
        problem.setType(URI.create(PROBLEM_BASE + "concurrent-update"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.debug("Rejected inventory request: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create(PROBLEM_BASE + "invalid-request"));
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflictState(IllegalStateException ex) {
        log.warn("Inventory invariant violated: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Inventory Conflict");
        problem.setType(URI.create(PROBLEM_BASE + "invariant-violated"));
        return problem;
    }
}
