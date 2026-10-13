package com.enterprise.mesh.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.enterprise.mesh.bff.api.BffViews.DashboardView;
import com.enterprise.mesh.bff.api.BffViews.StorefrontView;
import com.enterprise.mesh.bff.service.BffAggregationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The storefront's two aggregated surfaces.
 *
 * <p>Both return 200 even when a section failed. That is the point of the
 * {@code sections} array in the payload: the client renders what it got and marks
 * the rest as unavailable. Returning 502 would throw away the three services that
 * did answer, which is the difference between a degraded page and a broken one.
 *
 * <p>The one exception is a dead session, which fails the request outright and is
 * handled as a 401 - there is nothing to degrade to, because nothing behind it can
 * be fetched.
 */
@RestController
@RequestMapping("/bff/api")
@Tag(name = "BFF Storefront", description = "Aggregated page payloads for the SPA")
public class StorefrontController {

    private final BffAggregationService aggregationService;

    public StorefrontController(BffAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @GetMapping("/public/storefront")
    @Operation(summary = "Home page payload, anonymously browsable")
    public StorefrontView storefront() {
        return aggregationService.storefront();
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Account page payload: profile, cart, recent orders and recommendations")
    public DashboardView dashboard(HttpServletRequest request, HttpServletResponse response,
                                   Authentication authentication) {
        return aggregationService.dashboard(request, response, authentication);
    }
}