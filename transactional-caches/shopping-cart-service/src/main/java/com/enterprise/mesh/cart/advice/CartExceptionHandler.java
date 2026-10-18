package com.enterprise.mesh.cart.advice;

import java.net.URI;

import com.enterprise.mesh.cart.service.ShoppingCartService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps shopping-cart failures to RFC 9457 problem documents. */
@RestControllerAdvice
public class CartExceptionHandler {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/cart/");

    /**
     * A full cart is a 409 rather than a 500 because it is an ordinary, actionable
     * outcome: the shopper removes a line or checks out and continues.
     */
    @ExceptionHandler(ShoppingCartService.CartLimitExceededException.class)
    public ProblemDetail handleLimit(ShoppingCartService.CartLimitExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Cart Full");
        problem.setType(URI.create(BASE + "limit-exceeded"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Cart Request");
        problem.setType(URI.create(BASE + "invalid-request"));
        return problem;
    }
}