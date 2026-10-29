package com.enterprise.mesh.gateway;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment and inventory are read surfaces at the north-south edge.
 *
 * <p>Their mutations belong to the checkout saga, where order-service calls them
 * directly over the mesh. Exposing those POSTs through the gateway as well would
 * turn an internal write API into public surface for no product reason. The
 * service-level {@code SERVICE}/{@code ADMIN} checks still apply, but the edge
 * should not route a method it has no caller for.
 */
@SpringBootTest
class GatewayNorthSouthMethodScopeTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    @DisplayName("payment and inventory are GET-only at the north-south edge")
    void readSurfacesAreGetOnly() {
        RouteDefinition payment = route("payment");
        assertThat(predicateValues(payment, "Path")).containsExactly("/api/payments/**");
        assertThat(predicateValues(payment, "Method")).containsExactly("GET");

        RouteDefinition inventory = route("inventory");
        assertThat(predicateValues(inventory, "Path")).containsExactly("/api/inventory/**");
        assertThat(predicateValues(inventory, "Method")).containsExactly("GET");
    }

    @Test
    @DisplayName("routes with a UI write contract are not method-restricted")
    void mutationRoutesKeepTheirMethods() {
        // Guard against copying the payment/inventory predicate onto a route whose
        // UI contract includes a write: checkout, cart, profile, review submission,
        // AI interactions and the carrier webhook all need their non-GET verbs.
        Stream.of("order", "shopping-cart", "customer", "review-rating",
                        "ai-orchestrator", "shipping-webhooks")
                .forEach(id -> assertThat(predicateValues(route(id), "Method"))
                        .as("%s must not be method-restricted", id)
                        .isEmpty());
    }

    private List<String> predicateValues(RouteDefinition route, String predicateName) {
        return route.getPredicates().stream()
                .filter(predicate -> predicateName.equals(predicate.getName()))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .toList();
    }

    private RouteDefinition route(String id) {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions()
                .collectList().block();
        assertThat(routes).isNotNull();
        return routes.stream()
                .filter(definition -> id.equals(definition.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No route '" + id + "'; present: "
                        + routes.stream().map(RouteDefinition::getId).toList()));
    }
}
