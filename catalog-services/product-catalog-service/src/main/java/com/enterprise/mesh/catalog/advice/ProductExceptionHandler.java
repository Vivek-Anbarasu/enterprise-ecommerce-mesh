package com.enterprise.mesh.catalog.advice;

import java.net.URI;

import com.enterprise.mesh.catalog.service.ProductCatalogService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps catalogue failures to RFC 9457 problem documents. */
@RestControllerAdvice
public class ProductExceptionHandler {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/catalog/");

    @ExceptionHandler(ProductCatalogService.ProductNotFoundException.class)
    public ProblemDetail handleNotFound(ProductCatalogService.ProductNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Product Not Found");
        problem.setType(URI.create(BASE + "not-found"));
        return problem;
    }
}