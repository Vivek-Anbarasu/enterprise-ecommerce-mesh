package com.enterprise.mesh.bff.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.JsonNode;

import com.enterprise.mesh.bff.api.BffViews.CartCard;
import com.enterprise.mesh.bff.api.BffViews.CategoryCard;
import com.enterprise.mesh.bff.api.BffViews.DashboardView;
import com.enterprise.mesh.bff.api.BffViews.OrderCard;
import com.enterprise.mesh.bff.api.BffViews.ProductCard;
import com.enterprise.mesh.bff.api.BffViews.ProfileCard;
import com.enterprise.mesh.bff.api.BffViews.RecommendationCard;
import com.enterprise.mesh.bff.api.BffViews.SectionStatus;
import com.enterprise.mesh.bff.api.BffViews.StorefrontView;
import com.enterprise.mesh.bff.client.DownstreamException;
import com.enterprise.mesh.bff.client.MeshApiClient;
import com.enterprise.mesh.bff.config.BffProperties;
import com.enterprise.mesh.bff.session.SessionTokenService;
import com.enterprise.mesh.bff.session.SessionTokens;
import com.enterprise.mesh.observability.BusinessMetrics;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Fans one browser request out across several services and folds the answers into
 * a single payload.
 *
 * <p>This is the trade the BFF exists to make. Four sequential calls from the SPA
 * would be four round trips the shopper waits through and four places for a failure
 * to blank the page; here they run concurrently behind one budget and a failure
 * costs one widget rather than the whole view.
 *
 * <p>The budget is per-request, not per-call. Without that, four calls each allowed
 * the full timeout would let a page take four times as long to fail as to succeed,
 * which is backwards - a partial answer is worth more the sooner it arrives.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BffAggregationService {

    private static final String METRIC_DOMAIN = "bff_aggregation";

    private final MeshApiClient meshApiClient;
    private final BffViewMapper mapper;
    private final SessionTokenService sessionTokenService;
    private final BffProperties properties;
    private final BusinessMetrics metrics;
    private final Executor aggregationExecutor;
    /**
     * Anonymous home page.
     *
     * <p>One downstream call, not several. The category breakdown is derived from
     * the product page rather than fetched from a second endpoint, and rating
     * averages come from the catalog document itself. Fanning out here would add
     * latency and a second failure mode to a page that a shopper sees before they
     * have even signed in.
     */
    public StorefrontView storefront() {
        Section<List<ProductCard>> products = settle(submit(
                "catalog", () -> mapper.productCards(
                        meshApiClient.get("/api/products?page=0&size=" + BffViewMapper.FEATURED_LIMIT),
                        BffViewMapper.FEATURED_LIMIT)));

        List<ProductCard> cards = products.value() == null ? List.of() : products.value();

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        for (ProductCard card : cards) {
            if (card.category() != null && !card.category().isBlank()) {
                categoryCounts.merge(card.category(), 1L, Long::sum);
            }
        }
        List<CategoryCard> categories = categoryCounts.entrySet().stream()
                // Busiest first: the storefront nav is a short list and the
                // categories people actually browse should be at the top.
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new CategoryCard(entry.getKey(), entry.getValue()))
                .toList();

        return new StorefrontView(cards, categories, categoryCounts,
                List.of(products.toStatus()));
    }

    /**
     * Authenticated account page.
     *
     * <p>Resolving the session first is deliberate: an expired token is a
     * redirect-worthy condition that must fail the whole request, whereas a failing
     * downstream service must not. Checking the session before any fan-out means the
     * shopper is sent to sign in before four pointless calls are made.
     */
    public DashboardView dashboard(HttpServletRequest request, HttpServletResponse response,
                                 Authentication authentication) {
        SessionTokens tokens = sessionTokenService.resolve(request, response, authentication);

        // Submit everything first, wait afterwards. Joining inside submit() would
        // serialise the four calls into four round trips and make this method a
        // rename of what the SPA was already doing.
        CompletableFuture<Section<ProfileCard>> profileFuture = submit("customer",
                () -> mapper.profileCard(meshApiClient.get("/api/customers/me", tokens)));

        CompletableFuture<Section<CartCard>> cartFuture = submit("cart",
                () -> mapper.cartCard(meshApiClient.get("/api/cart", tokens)));

        CompletableFuture<Section<List<OrderCard>>> ordersFuture = submit("orders",
                () -> mapper.orderCards(
                        meshApiClient.get("/api/orders?page=0&size=" + BffViewMapper.RECENT_ORDER_LIMIT, tokens),
                        BffViewMapper.RECENT_ORDER_LIMIT));

        CompletableFuture<Section<Recommendations>> recommendationsFuture = submit("recommendations",
                () -> readRecommendations(meshApiClient.get("/api/ai/recommendations", tokens)));

        Section<ProfileCard> profile = settle(profileFuture);
        Section<CartCard> cart = settle(cartFuture);
        Section<List<OrderCard>> orders = settle(ordersFuture);
        Section<Recommendations> recommendations = settle(recommendationsFuture);

        List<SectionStatus> sections = List.of(
                profile.toStatus(), cart.toStatus(), orders.toStatus(), recommendations.toStatus());

        Recommendations rec = recommendations.value() == null
                ? new Recommendations(List.of(), List.of(), null)
                : recommendations.value();

        return new DashboardView(
                profile.value(),
                cart.value(),
                orders.value() == null ? List.of() : orders.value(),
                rec.cards(),
                rec.preferred(),
                rec.fallback(),
                sections);
    }

    private Recommendations readRecommendations(JsonNode response) {
        return new Recommendations(
                mapper.recommendationCards(response),
                mapper.preferredCategories(response),
                mapper.fallbackReason(response));
    }

    /**
     * Dispatches one section onto the aggregation executor, already carrying its
     * share of the timeout.
     *
     * <p>{@code completeOnTimeout} rather than {@code orTimeout}: it settles the
     * future with a failed section, so the join in {@link #settle} never throws and
     * callers need no try/catch per field. The abandoned task still finishes on its
     * own - the per-call read timeout is strictly shorter than this budget, which
     * is what stops a hung call from leaking a thread indefinitely.
     */
    private <T> CompletableFuture<Section<T>> submit(String name, Supplier<T> call) {
        try {
            // The explicit type witness matters: without it supplyAsync infers the
            // lambda's return as Section<? extends Object> and completeOnTimeout,
            // which needs the same element type as its receiver, stops compiling.
            return CompletableFuture.<Section<T>>supplyAsync(() -> {
                try {
                    T value = call.get();
                    metrics.recordOutcome(METRIC_DOMAIN, name + "_ok");
                    return Section.ok(name, value);
                } catch (RuntimeException ex) {
                    metrics.recordOutcome(METRIC_DOMAIN, name + "_error");
                    log.warn("Aggregation section failed name={} reason={}", name, ex.toString());
                    return Section.failed(name, describe(ex));
                }
            }, aggregationExecutor)
                    .completeOnTimeout(Section.failed(name, "Took too long to respond"),
                            properties.getAggregationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            // Executor shutting down mid-request. Degrade this section rather than
            // failing the page.
            log.warn("Aggregation executor rejected section={}", name);
            metrics.recordOutcome(METRIC_DOMAIN, name + "_rejected");
            return CompletableFuture.<Section<T>>completedFuture(
                    Section.failed(name, "The service is briefly unavailable"));
        }
    }

    /**
     * Waits for a submitted section. The future is always already settled or will
     * settle within the aggregation budget, so this cannot block indefinitely.
     */
    private <T> Section<T> settle(CompletableFuture<Section<T>> future) {
        return future.join();
    }

    /**
     * Turns a failure into something safe to hand a browser.
     *
     * <p>Only {@link DownstreamException} messages are surfaced, and those were
     * written by this service for exactly that purpose. Anything else - a
     * NullPointerException, a serialisation bug - would otherwise put an internal
     * class name and a stack frame's worth of context in a shopper's devtools.
     */
    private String describe(Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        if (cause instanceof DownstreamException downstream) {
            return downstream.getMessage();
        }
        return "The service is temporarily unavailable";
    }

    /** Outcome of one aggregation section: a value, or the reason there isn't one. */
    private record Section<T>(String name, T value, String failure) {

        static <T> Section<T> ok(String name, T value) {
            return new Section<>(name, value, null);
        }

        static <T> Section<T> failed(String name, String failure) {
            return new Section<>(name, null, failure);
        }

        SectionStatus toStatus() {
            return failure == null
                    ? SectionStatus.ok(name)
                    : SectionStatus.failed(name, failure);
        }
    }

    /** Recommendations arrive together with the "no history yet" marker. */
    private record Recommendations(List<RecommendationCard> cards,
                                   List<String> preferred,
                                   String fallback) {
    }
}