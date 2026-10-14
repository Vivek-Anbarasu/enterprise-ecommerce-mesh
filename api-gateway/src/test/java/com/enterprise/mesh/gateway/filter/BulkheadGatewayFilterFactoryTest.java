package com.enterprise.mesh.gateway.filter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour of the per-route bulkhead filter.
 *
 * <p>Most of these tests are about one property: a permit must always come back.
 * A leaked permit is invisible - no exception, no failed request, no metric - and
 * it can only ever make the compartment smaller, so a bulkhead that leaks under
 * load degrades into a route that rejects everyone, usually some minutes after
 * launch and with nothing in the logs to say why. The cancellation test is the one
 * that matters most: a shopper closing the tab mid-inference is ordinary traffic
 * on the AI route, not an edge case.
 */
class BulkheadGatewayFilterFactoryTest {

    private static final String AI = "aiBulkhead";
    private static final String PAYMENT = "paymentBulkhead";
    private static final int ONE_PERMIT = 1;

    /** Exchange handed to the DispatcherHandler when a request is shed and forwarded. */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    // ---------------------------------------------------------------- permits returned

    @Test
    @DisplayName("a permit is held while the downstream call is in flight")
    void permitIsHeldDuringTheCall() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);
        Sinks.Empty<Void> never = Sinks.empty();

        Disposable inFlight = filterFor(registry, AI, "forward:/fallback/ai")
                .filter(exchange(), exchange -> never.asMono())
                .subscribe();

        assertThat(bulkhead(registry, AI).getMetrics().getAvailableConcurrentCalls()).isZero();

        inFlight.dispose();
    }

    @Test
    @DisplayName("a permit is returned when the downstream call completes")
    void permitIsReturnedOnCompletion() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);

        filterFor(registry, AI, "forward:/fallback/ai")
                .filter(exchange(), exchange -> Mono.empty())
                .block();

        assertThat(bulkhead(registry, AI).getMetrics().getAvailableConcurrentCalls()).isEqualTo(ONE_PERMIT);
    }

    @Test
    @DisplayName("a permit is returned when the downstream call fails")
    void permitIsReturnedOnFailure() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);

        filterFor(registry, AI, "forward:/fallback/ai")
                .filter(exchange(), exchange -> Mono.error(new IllegalStateException("backend down")))
                .onErrorResume(t -> Mono.empty())
                .block();

        assertThat(bulkhead(registry, AI).getMetrics().getAvailableConcurrentCalls()).isEqualTo(ONE_PERMIT);
    }

    @Test
    @DisplayName("a permit is returned when the client disconnects mid-call")
    void permitIsReturnedOnCancellation() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);
        Sinks.Empty<Void> never = Sinks.empty();

        Disposable inFlight = filterFor(registry, AI, "forward:/fallback/ai")
                .filter(exchange(), exchange -> never.asMono())
                .subscribe();

        // The shopper closed the tab. The chain is cancelled, not completed, and the
        // permit has to go back anyway - otherwise every abandoned AI request would
        // cost the compartment one slot permanently.
        inFlight.dispose();

        assertThat(bulkhead(registry, AI).getMetrics().getAvailableConcurrentCalls()).isEqualTo(ONE_PERMIT);
    }

    // ---------------------------------------------------------------- shedding

    @Test
    @DisplayName("a full bulkhead sheds the request without calling the backend")
    void fullBulkheadShedsWithoutCallingTheBackend() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);
        // Occupy the only permit, as a slow inference call would.
        assertThat(bulkhead(registry, AI).tryAcquirePermission()).isTrue();
        AtomicBoolean backendCalled = new AtomicBoolean();

        filterFor(registry, AI, "forward:/fallback/ai")
                .filter(exchange(), exchange -> Mono.fromRunnable(() -> backendCalled.set(true)).then())
                .block();

        assertThat(backendCalled).isFalse();
    }

    @Test
    @DisplayName("a shed request is forwarded to the fallback and says which bulkhead shed it")
    void shedRequestIsForwardedWithTheReason() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);
        assertThat(bulkhead(registry, AI).tryAcquirePermission()).isTrue();
        ServerWebExchange shed = exchange();

        filterFor(registry, AI, "forward:/fallback/ai")
                .filter(shed, exchange -> Mono.empty())
                .block();

        ServerWebExchange seen = forwarded.get();
        assertThat(seen).isNotNull();
        assertThat(seen.getRequest().getURI().getPath()).isEqualTo("/fallback/ai");
        // FallbackController reads this to report a shed rather than an open circuit.
        String shedBy = shed.getAttribute(BulkheadGatewayFilterFactory.REJECTION_ATTR);
        assertThat(shedBy).isEqualTo(AI);
    }

    @Test
    @DisplayName("a shed request without a configured fallback surfaces as a rejection error")
    void shedWithoutFallbackIsAnError() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);
        assertThat(bulkhead(registry, AI).tryAcquirePermission()).isTrue();
        AtomicReference<Throwable> rejection = new AtomicReference<>();

        filterFor(registry, AI, null)
                .filter(exchange(), exchange -> Mono.empty())
                .doOnError(rejection::set)
                .onErrorResume(t -> Mono.empty())
                .block();

        assertThat(rejection.get()).isInstanceOf(BulkheadFullException.class);
    }

    @Test
    @DisplayName("a full AI bulkhead does not shed a payment request")
    void bulkheadsAreIsolatedPerRoute() {
        // The whole point of per-route compartments. Sharing one would reintroduce the
        // cross-route contention they exist to remove: a slow AI call must never be
        // able to reject a payment.
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI, PAYMENT);
        assertThat(bulkhead(registry, AI).tryAcquirePermission()).isTrue();
        AtomicBoolean backendCalled = new AtomicBoolean();

        filterFor(registry, PAYMENT, "forward:/fallback/payments")
                .filter(exchange(), exchange -> Mono.fromRunnable(() -> backendCalled.set(true)).then())
                .block();

        assertThat(backendCalled).isTrue();
    }

    @Test
    @DisplayName("the configured capacity, not the registry default, is the ceiling")
    void configuredCapacityIsApplied() {
        // Guards against silently inheriting BulkheadConfig.DEFAULT_MAX_CONCURRENT_CALLS
        // (25). If the configuration were ignored the eleventh request here would still
        // reach the backend, leaving the gateway with far more concurrent AI calls than
        // intended and none of the isolation it was supposed to buy.
        BulkheadConfig ten = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build();
        BulkheadRegistry registry = registryOf(ten, AI);
        for (int i = 0; i < 10; i++) {
            assertThat(bulkhead(registry, AI).tryAcquirePermission()).isTrue();
        }
        AtomicBoolean backendCalled = new AtomicBoolean();

        // The eleventh concurrent AI call.
        filterFor(registry, AI, "forward:/fallback/ai")
                .filter(exchange(), exchange -> Mono.fromRunnable(() -> backendCalled.set(true)).then())
                .block();

        assertThat(backendCalled).isFalse();
        assertThat(bulkhead(registry, AI).getMetrics().getAvailableConcurrentCalls()).isZero();
    }

    // ---------------------------------------------------------------- startup guards

    @Test
    @DisplayName("a bulkhead name with no matching configuration fails at startup")
    void unconfiguredBulkheadNameFailsFast() {
        BulkheadRegistry registry = registryOf(onePermitConfig(), AI);

        assertThatThrownBy(() -> filterFactory(registry).apply(config("aTypo", "forward:/fallback/ai")))
                .isInstanceOf(IllegalStateException.class)
                // The message matters: without it this failure reads as a mysterious
                // startup crash rather than a typo in the YAML.
                .hasMessageContaining("resilience4j.bulkhead.instances.aTypo");
    }

    @Test
    @DisplayName("a bulkhead configured to wait for a permit fails at startup")
    void waitingBulkheadFailsFast() {
        // maxWaitDuration > 0 makes tryAcquirePermission() park the calling thread.
        // In this gateway that thread is a Netty event loop, so a "helpful" retry
        // would reintroduce exactly the starvation the bulkhead exists to prevent.
        BulkheadConfig waiting = BulkheadConfig.custom()
                .maxConcurrentCalls(ONE_PERMIT)
                .maxWaitDuration(Duration.ofSeconds(2))
                .build();

        assertThatThrownBy(() -> filterFactory(registryOf(waiting, AI)).apply(config(AI, "forward:/fallback/ai")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxWaitDuration");
    }

    // ---------------------------------------------------------------- helpers

    private static BulkheadConfig onePermitConfig() {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(ONE_PERMIT)
                .maxWaitDuration(Duration.ZERO)
                .build();
    }

    /**
     * Builds a registry shaped like the one the Spring auto-configuration produces:
     * every entry under {@code resilience4j.bulkhead.instances} is already
     * instantiated by the time routes are built.
     */
    private static BulkheadRegistry registryOf(BulkheadConfig config, String... names) {
        Map<String, BulkheadConfig> configs = new LinkedHashMap<>();
        for (String name : names) {
            configs.put(name, config);
        }
        BulkheadRegistry registry = BulkheadRegistry.of(configs);
        for (String name : names) {
            registry.bulkhead(name, config);
        }
        return registry;
    }

    /** The already-instantiated bulkhead; safe because it was created above, with its config. */
    private static Bulkhead bulkhead(BulkheadRegistry registry, String name) {
        return registry.bulkhead(name);
    }

    private static BulkheadGatewayFilterFactory.Config config(String name, String fallbackUri) {
        BulkheadGatewayFilterFactory.Config config = new BulkheadGatewayFilterFactory.Config().setName(name);
        return fallbackUri == null ? config : config.setFallbackUri(fallbackUri);
    }

    private static ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/ai/recommendations").build());
    }

    private GatewayFilter filterFor(BulkheadRegistry registry, String name, String fallbackUri) {
        return filterFactory(registry).apply(config(name, fallbackUri));
    }

    private BulkheadGatewayFilterFactory filterFactory(BulkheadRegistry registry) {
        DispatcherHandler dispatcher = new DispatcherHandler() {
            @Override
            public Mono<Void> handle(ServerWebExchange exchange) {
                forwarded.set(exchange);
                return Mono.empty();
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("dispatcherHandler", dispatcher);

        ObjectProvider<DispatcherHandler> provider = beans.getBeanProvider(DispatcherHandler.class);
        return new BulkheadGatewayFilterFactory(registry, provider);
    }
}