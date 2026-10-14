package com.enterprise.mesh.gateway.filter;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import reactor.core.publisher.Mono;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.HasRouteId;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.containsEncodedParts;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.handle;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.reset;

/**
 * Per-route concurrency isolation, built on a Resilience4j semaphore bulkhead.
 *
 * <p>The gateway is reactive. Every in-flight request routed to a downstream service
 * holds a pooled Reactor Netty connection for as long as that call takes, and all
 * routes share one connection pool and one {@code boundedElastic} scheduler. That
 * makes the danger here not a thread pool being drained by blocking work - the
 * event loops never block - but a shared, finite set of connections and scheduler
 * slots being held for a long time by one slow backend.
 *
 * <p>Which is exactly the AI shape. {@code aiCircuitBreaker} allows a call to run for
 * thirty seconds, so a modest burst of slow inference calls pins a proportional slice
 * of the pool for half a minute each. Without a cap, the pool is a shared resource
 * being spent entirely on recommendations while a shopper trying to load a product
 * page or reach checkout queues behind them. Rate limiting does not help: it bounds
 * arrivals per tenant per second, not how many of those arrivals may be in flight
 * simultaneously against one slow dependency.
 *
 * <p>A bulkhead closes that gap by capping in-flight concurrency per route and
 * <em>rejecting</em> the surplus rather than queueing it. Queueing would reintroduce
 * the problem in a worse form, since every queued request is still an open connection
 * and an open client socket. Rejection is instant, costs nothing, and leaves the
 * catalogue and checkout routes untouched.
 *
 * <p>Placed inside the circuit breaker (last in the filter list), so that an already
 * open circuit rejects without consuming a permit, and outside it, so that a slow but
 * healthy backend does. Both guards are complementary: the circuit breaker bounds how
 * long the gateway keeps trying a broken dependency, the bulkhead bounds how many
 * callers may be trying it at once.
 *
 * <p>This is a hand-written factory rather than a configuration toggle because
 * Spring Cloud Gateway's built-in {@code CircuitBreaker} filter builds a
 * {@code ReactiveResilience4JCircuitBreaker}, which composes a circuit breaker and a
 * time limiter and has no bulkhead stage at all. There is no property that turns one
 * on.
 *
 * @see com.enterprise.mesh.gateway.fallback.FallbackController
 */
@Component
@Slf4j
public class BulkheadGatewayFilterFactory extends AbstractGatewayFilterFactory<BulkheadGatewayFilterFactory.Config> {

    /** Name used for this filter in {@code application.yml}. */
    public static final String NAME = "Bulkhead";

    /**
     * Exchange attribute naming the bulkhead that shed the current request.
     *
     * <p>Read by {@code FallbackController} so the log line and the client-facing
     * detail report the cause that actually applied. Without it a shed request would
     * be logged as "circuit breaker open", which sends whoever is on call looking at
     * a circuit that is closed and healthy.
     */
    public static final String REJECTION_ATTR = "gateway.bulkhead.rejectedBy";

    /**
     * How often a full bulkhead is logged at WARN for a given route.
     *
     * <p>Rejection is the normal outcome under sustained overload, so logging every
     * one of them would turn the load spike into a log-flooding incident and bury the
     * request that actually explains the spike. The rate limiter upstream bounds
     * arrivals, but not tightly enough to make per-rejection logging safe. Resilience4j
     * publishes a rejected-calls counter for the true rate; the WARN exists to tell an
     * operator that shedding has started, and it stays that rare.
     */
    private static final long REJECTION_LOG_INTERVAL = Duration.ofSeconds(10).toNanos();

    private final BulkheadRegistry bulkheadRegistry;

    private final ObjectProvider<DispatcherHandler> dispatcherHandlerProvider;

    /** Per-route, so one saturated route cannot mute the WARN for another. */
    private final AtomicLong nextRejectionLogAt = new AtomicLong();

    private volatile DispatcherHandler dispatcherHandler;

    public BulkheadGatewayFilterFactory(BulkheadRegistry bulkheadRegistry,
                                        ObjectProvider<DispatcherHandler> dispatcherHandlerProvider) {
        super(Config.class);
        this.bulkheadRegistry = bulkheadRegistry;
        this.dispatcherHandlerProvider = dispatcherHandlerProvider;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        // Deliberately does not expose maxConcurrentCalls. Capacity is configured once,
        // under resilience4j.bulkhead.instances, which is also what the Micrometer
        // meters are registered against; accepting it here too would give the same
        // number two homes and let them disagree.
        return List.of(NAME_KEY, "fallbackUri");
    }

    @Override
    public GatewayFilter apply(Config config) {
        Bulkhead bulkhead = resolve(config.getId());

        return new GatewayFilter() {

            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                // defer, not eager: the permit must be taken when the exchange is
                // subscribed to, not when this filter is built at route-load time.
                return Mono.defer(() -> {
                    if (!bulkhead.tryAcquirePermission()) {
                        return shed(exchange, config, bulkhead);
                    }
                    // doFinally, not doOnTerminate or doOnSuccess. A shopper who closes
                    // the tab mid-inference cancels the chain, and onComplete() is the
                    // only signal guaranteed to run for success, failure and
                    // cancellation alike. Anything narrower leaks a permit on every
                    // disconnect, and a bulkhead only ever shrinks as permits leak -
                    // the route would be dead to new traffic minutes after launch, with
                    // no error anywhere to explain it.
                    return chain.filter(exchange).doFinally(signalType -> bulkhead.onComplete());
                });
            }

            @Override
            public String toString() {
                return filterToStringCreator(BulkheadGatewayFilterFactory.this)
                        .append(NAME_KEY, bulkhead.getName())
                        .append("maxConcurrentCalls",
                                String.valueOf(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()))
                        .toString();
            }
        };
    }

    /**
     * Looks up the configured bulkhead, rejecting the two configurations that would
     * silently defeat the isolation at startup rather than at 3am under load.
     */
    private Bulkhead resolve(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalStateException(
                    "The " + NAME + " filter on this route has no name. The name selects the "
                            + "bulkhead from resilience4j.bulkhead.instances and is the only thing "
                            + "that gives this route its own compartment.");
        }
        // find(), never bulkhead(name). bulkhead(name) creates an instance on demand
        // from the registry default, so an unknown name would look configured right here
        // and then quietly run at the default 25 concurrent calls - exactly the silent
        // failure this check exists to catch. The auto-configuration eagerly
        // instantiates every entry under resilience4j.bulkhead.instances, so a
        // correctly named bulkhead is already present by the time routes are built.
        Bulkhead bulkhead = bulkheadRegistry.find(name)
                .orElseThrow(() -> new IllegalStateException(
                        "The " + NAME + " filter names bulkhead '" + name + "', but no "
                                + "resilience4j.bulkhead.instances." + name + " is configured."));
        BulkheadConfig config = bulkhead.getBulkheadConfig();
        if (!config.getMaxWaitDuration().isZero()) {
            throw new IllegalStateException(
                    "Bulkhead '" + name + "' sets maxWaitDuration=" + config.getMaxWaitDuration()
                            + ". This gateway is reactive and tryAcquirePermission() waits on the "
                            + "calling Netty event-loop thread while it tries, so any wait parks an "
                            + "event loop in exactly the way the bulkhead exists to prevent. Set "
                            + "maxWaitDuration to 0 so a full bulkhead rejects immediately.");
        }
        return bulkhead;
    }

    /**
     * Rejects a request that found its compartment full.
     *
     * <p>With a fallback configured the request is forwarded to it, so a shed caller
     * sees the same RFC 9457 payload it already knows how to handle from a circuit
     * breaker, rather than a raw 500. The status is 503, not 429: 429 means this
     * caller is over its personal quota, and it is already covered by the per-tenant
     * rate limiter upstream. 503 is the honest signal - the server is shedding, not you.
     */
    private Mono<Void> shed(ServerWebExchange exchange, Config config, Bulkhead bulkhead) {
        URI fallbackUri = config.getFallbackUri();
        if (fallbackUri == null) {
            // No fallback configured: let the rejection surface as an error rather than
            // inventing a response contract for this route that clients cannot expect.
            return Mono.error(BulkheadFullException.createBulkheadFullException(bulkhead));
        }
        logRejection(bulkhead, exchange);

        // Forwarding preserves the same exchange, so this survives the re-dispatch for
        // FallbackController to read.
        exchange.getAttributes().put(REJECTION_ATTR, bulkhead.getName());

        // Mirrors SpringCloudCircuitBreakerFilterFactory's fallback mechanics; the scheme
        // stays 'forward' so ForwardRoutingFilter hands off to the DispatcherHandler.
        URI uri = exchange.getRequest().getURI();
        URI requestUrl = UriComponentsBuilder.fromUri(uri)
                .host(null)
                .port(null)
                .uri(URI.create(fallbackUri.getScheme() + ":" + fallbackUri.getPath()))
                .scheme(null)
                .build(containsEncodedParts(uri))
                .toUri();

        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);
        reset(exchange);

        ServerHttpRequest request = exchange.getRequest().mutate().uri(requestUrl).build();
        return handle(dispatcherHandler(), exchange.mutate().request(request).build());
    }

    private void logRejection(Bulkhead bulkhead, ServerWebExchange exchange) {
        String route = exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath();
        long now = System.nanoTime();
        long nextAllowed = nextRejectionLogAt.get();
        if (now >= nextAllowed && nextRejectionLogAt.compareAndSet(nextAllowed, now + REJECTION_LOG_INTERVAL)) {
            log.warn("Bulkhead '{}' is full; shedding {} instead of queueing it. "
                    + "Further rejections on this route are logged at DEBUG until it drains. "
                    + "Rejected-call count is on the resilience4j.bulkhead meters.",
                    bulkhead.getName(), route);
        } else {
            log.debug("Bulkhead '{}' is full; shedding {}", bulkhead.getName(), route);
        }
    }

    private DispatcherHandler dispatcherHandler() {
        DispatcherHandler handler = dispatcherHandler;
        if (handler == null) {
            handler = dispatcherHandlerProvider.getIfAvailable();
            if (handler == null) {
                throw new IllegalStateException(
                        "DispatcherHandler is not available; the " + NAME + " filter cannot forward "
                                + "to its fallback. A filter factory needs ObjectProvider<DispatcherHandler> "
                                + "to avoid a circular dependency with the dispatcher itself.");
            }
            dispatcherHandler = handler;
        }
        return handler;
    }

    /**
     * @param name        bulkhead name, which selects the entry under
     *                    {@code resilience4j.bulkhead.instances}
     * @param fallbackUri where to send a shed request, conventionally the same
     *                    {@code forward:/fallback/...} target the route's circuit
     *                    breaker uses
     */
    public static class Config implements HasRouteId {

        private String name;

        private String routeId;

        private URI fallbackUri;

        @Override
        public void setRouteId(String routeId) {
            this.routeId = routeId;
        }

        @Override
        public String getRouteId() {
            return routeId;
        }

        /** Falls back to the route id so an unnamed filter still gets its own compartment. */
        public String getId() {
            return StringUtils.hasText(name) ? name : routeId;
        }

        public String getName() {
            return name;
        }

        public Config setName(String name) {
            this.name = name;
            return this;
        }

        public URI getFallbackUri() {
            return fallbackUri;
        }

        public Config setFallbackUri(URI fallbackUri) {
            this.fallbackUri = fallbackUri;
            return this;
        }

        public Config setFallbackUri(String fallbackUri) {
            return setFallbackUri(URI.create(fallbackUri));
        }

        @Override
        public String toString() {
            return "Bulkhead Config{name='" + name + "', routeId='" + routeId
                    + "', fallbackUri='" + fallbackUri + "'}";
        }
    }
}