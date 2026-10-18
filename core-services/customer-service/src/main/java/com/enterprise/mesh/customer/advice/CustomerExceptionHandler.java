package com.enterprise.mesh.customer.advice;

import java.net.URI;

import com.enterprise.mesh.customer.service.CustomerService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps customer-service failures to RFC 9457 problem documents. */
@RestControllerAdvice
public class CustomerExceptionHandler {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/customer/");

    @ExceptionHandler(CustomerService.CustomerNotFoundException.class)
    public ProblemDetail handleNotFound(CustomerService.CustomerNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Customer Not Found");
        problem.setType(URI.create(BASE + "not-found"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Customer Request");
        problem.setType(URI.create(BASE + "invalid-request"));
        return problem;
    }
}