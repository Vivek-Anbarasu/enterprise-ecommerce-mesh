package com.enterprise.mesh.review.advice;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps review failures to RFC 9457 problem documents. */
@RestControllerAdvice
public class ReviewExceptionHandler {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/review/");

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Review");
        problem.setType(URI.create(BASE + "invalid-request"));
        return problem;
    }
}