package com.enterprise.mesh.catalog.controller;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.enterprise.mesh.catalog.domain.Product;
import com.enterprise.mesh.catalog.service.ProductCatalogService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Catalogue", description = "Product browse and management")
public class ProductController {

    private final ProductCatalogService catalogService;

    public ProductController(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public record ProductPage(List<Product> content, long totalElements, int page, int size) {
    }

    @GetMapping
    @Operation(summary = "Browse products, optionally filtered by category")
    public ProductPage browse(@RequestParam(required = false) String category,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        String tenant = tenantId == null ? "public" : tenantId;
        return new ProductPage(catalogService.browse(category, tenant, page, size),
                catalogService.count(category, tenant), page, size);
    }

    @GetMapping("/sku/{sku}")
    @Operation(summary = "Fetch a product by SKU")
    public Product bySku(@PathVariable String sku,
                         @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return catalogService.findBySku(sku, tenantId == null ? "public" : tenantId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a product by id")
    public Product byId(@PathVariable String id,
                        @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return catalogService.findById(id, tenantId == null ? "public" : tenantId);
    }

    @PostMapping
    @Operation(summary = "Create a product")
    public ResponseEntity<Product> create(@RequestBody Product product) {
        Product saved = catalogService.create(product);
        return ResponseEntity
                .created(URI.create("/api/products/" + saved.getId()))
                .body(saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a product")
    public Product update(@PathVariable String id,
                          @RequestBody Product product,
                          @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return catalogService.update(id, product, tenantId == null ? "public" : tenantId);
    }

    @PostMapping("/sku/{sku}/deactivate")
    @Operation(summary = "Soft delete a product, preserving order history")
    public ResponseEntity<Void> deactivate(@PathVariable String sku,
                                           @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        catalogService.deactivate(sku, tenantId == null ? "public" : tenantId);
        return ResponseEntity.noContent().build();
    }

    @RestControllerAdvice
    static class ProductExceptionHandler {

        @ExceptionHandler(ProductCatalogService.ProductNotFoundException.class)
        ProblemDetail handleNotFound(ProductCatalogService.ProductNotFoundException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
            problem.setTitle("Product Not Found");
            problem.setType(URI.create("https://errors.enterprise-ecommerce.com/catalog/not-found"));
            return problem;
        }
    }
}
