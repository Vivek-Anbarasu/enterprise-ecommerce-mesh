package com.enterprise.mesh.order.controller;

import java.net.URI;
import java.util.Map;

import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.saga.CheckoutSagaOrchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps order-service failures to RFC 9457 problem documents. */
@RestControllerAdvice
public class OrderExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);
    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/order/");

    @ExceptionHandler(OrderController.OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderController.OrderNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Order Not Found");
        problem.setType(URI.create(BASE + "not-found"));
        return problem;
    }

    /**
     * "Sold out while you were checking out" is a normal, actionable outcome,
     * so it maps to 409 rather than 500 - the storefront shows an alternative
     * rather than a generic error.
     */
    @ExceptionHandler(CheckoutSagaOrchestrator.InsufficientStockException.class)
    public ProblemDetail handleOutOfStock(CheckoutSagaOrchestrator.InsufficientStockException ex) {
        log.info("Checkout rejected for out-of-stock: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Item No Longer Available");
        problem.setType(URI.create(BASE + "insufficient-stock"));
        return problem;
    }

    @ExceptionHandler(CheckoutSagaOrchestrator.PaymentDeclinedException.class)
    public ProblemDetail handleDeclined(CheckoutSagaOrchestrator.PaymentDeclinedException ex) {
        log.info("Checkout rejected: payment declined");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "The payment was declined. No money has been taken.");
        problem.setTitle("Payment Declined");
        problem.setType(URI.create(BASE + "payment-declined"));
        problem.setProperties(Map.of("reason", ex.getMessage()));
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.warn("Rejected order request: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid Order Transition");
        problem.setType(URI.create(BASE + "invalid-transition"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create(BASE + "invalid-request"));
        return problem;
    }
}
