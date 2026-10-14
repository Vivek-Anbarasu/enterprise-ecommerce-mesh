package com.enterprise.mesh.gateway;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the bulkheads in {@code application.yml} are real and are wired to the
 * right routes.
 *
 * <p>Worth a context test rather than trusting the YAML. Two failure modes are
 * invisible until the context is actually built, and both would leave the gateway
 * looking healthy while providing no isolation at all:
 *
 * <ul>
 *   <li>The {@code resilience4j-bulkhead} artifact is not a transitive dependency of
 *       the circuit breaker starter. Without it there is no {@code Bulkhead} type on
 *       the classpath, {@code BulkheadAutoConfiguration} backs off, there is no
 *       {@code BulkheadRegistry}, and the filter cannot start - loudly, at least.
 *   <li>Even with the artifact present, an unknown bulkhead name resolves to
 *       {@code BulkheadConfig.DEFAULT_MAX_CONCURRENT_CALLS} rather than failing.
 *       That one is silent: the route would run with 25 concurrent AI calls instead
 *       of the configured 10, and nothing anywhere would say so.
 * </ul>
 *
 * <p>Also the first test to actually build this context, which is why it is here at
 * all: the readiness health group referenced a {@code db} contributor the gateway
 * does not have, which aborted startup outright. Asserting the context loads is what
 * surfaced that.
 */
@SpringBootTest
class GatewayBulkheadConfigurationTest {

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    @DisplayName("the bulkhead registry exists, so the resilience4j-bulkhead dependency is present")
    void bulkheadRegistryIsAvailable() {
        assertThat(bulkheadRegistry).isNotNull();
    }

    @Test
    @DisplayName("the AI compartment holds the 10 concurrent calls it was configured for")
    void aiBulkheadIsConfigured() {
        Bulkhead ai = bulkheadRegistry.bulkhead("aiBulkhead");

        assertThat(ai.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(10);
        // Reject immediately. A non-zero wait here would park Netty event-loop threads,
        // which BulkheadGatewayFilterFactory rejects at startup for exactly that reason.
        assertThat(ai.getBulkheadConfig().getMaxWaitDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("the payment compartment holds the 25 concurrent calls it was configured for")
    void paymentBulkheadIsConfigured() {
        assertThat(bulkheadRegistry.bulkhead("paymentBulkhead").getBulkheadConfig().getMaxConcurrentCalls())
                .isEqualTo(25);
    }

    @Test
    @DisplayName("the AI route sheds to the AI fallback")
    void aiRouteIsBounded() {
        assertThat(filterArgs(route("ai-orchestrator"), "Bulkhead"))
                .containsEntry("name", "aiBulkhead")
                .containsEntry("fallbackUri", "forward:/fallback/ai");
    }

    @Test
    @DisplayName("the payment route sheds to the payment fallback")
    void paymentRouteIsBounded() {
        assertThat(filterArgs(route("payment"), "Bulkhead"))
                .containsEntry("name", "paymentBulkhead")
                .containsEntry("fallbackUri", "forward:/fallback/payments");
    }

    @Test
    @DisplayName("the bulkhead sits inside the circuit breaker on both bounded routes")
    void bulkheadIsInsideTheCircuitBreaker() {
        // Ordering is the whole design. The circuit breaker sheds an open circuit
        // without spending a permit, and the bulkhead caps only the calls that are
        // genuinely still going downstream. Reversed, every circuit-open rejection
        // would burn a permit for nothing.
        assertBoundedInsideBreaker("ai-orchestrator", "aiCircuitBreaker");
        assertBoundedInsideBreaker("payment", "paymentCircuitBreaker");
    }

    @Test
    @DisplayName("routes without a slow dependency are left unbounded")
    void otherRoutesAreUnbounded() {
        // Deliberate. The catalogue and cart are the routes that must stay available
        // during an AI incident; capping them would only give a second thing to fail.
        assertThat(filterNames(route("product-catalog"))).doesNotContain("Bulkhead");
        assertThat(filterNames(route("shopping-cart"))).doesNotContain("Bulkhead");
        assertThat(filterNames(route("inventory"))).doesNotContain("Bulkhead");
    }

    // ---------------------------------------------------------------- helpers

    private void assertBoundedInsideBreaker(String routeId, String breakerName) {
        List<String> filters = filterNames(route(routeId));
        int breaker = filters.indexOf("CircuitBreaker");
        int bulkhead = filters.indexOf("Bulkhead");

        assertThat(breaker).as("%s must still have a circuit breaker", routeId).isNotNegative();
        assertThat(bulkhead).as("%s must have a bulkhead", routeId).isNotNegative();
        assertThat(bulkhead)
                .as("%s: the bulkhead must run after the circuit breaker", routeId)
                .isGreaterThan(breaker);
        assertThat(filterArgs(route(routeId), "CircuitBreaker")).containsEntry("name", breakerName);
    }

    private RouteDefinition route(String id) {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        List<String> ids = routes.stream().map(RouteDefinition::getId).toList();
        return routes.stream()
                .filter(definition -> id.equals(definition.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No route '" + id + "' is configured. Configured routes: " + ids));
    }

    private static List<String> filterNames(RouteDefinition definition) {
        return definition.getFilters().stream().map(FilterDefinition::getName).toList();
    }

    private static java.util.Map<String, String> filterArgs(RouteDefinition definition, String filterName) {
        return definition.getFilters().stream()
                .filter(filter -> filterName.equals(filter.getName()))
                .findFirst()
                .map(FilterDefinition::getArgs)
                .orElseThrow(() -> new AssertionError(
                        "Route '" + definition.getId() + "' has no " + filterName + " filter"));
    }
}