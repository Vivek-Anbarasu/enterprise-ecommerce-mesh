package com.enterprise.mesh.bff.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The contract the storefront sees.
 *
 * <p>These records are the BFF's actual reason to exist. They are not mirrors of the
 * downstream DTOs: they are deliberately smaller, flatter and shaped for rendering
 * a page. Because they are separate types, a service can add a field, rename an
 * enum or reshape a response without the SPA's types changing, and the BFF can
 * reshape one service's output without dragging the other four along.
 *
 * <p>Every field the SPA renders is nullable or defaulted. These payloads cross a
 * service boundary that the BFF does not own, so a missing field must degrade the
 * view rather than fail the page.
 */
public final class BffViews {

    private BffViews() {
    }

    /**
     * Outcome of one section of an aggregation.
     *
     * <p>A partial page beats a blank one: if reviews are down, the shopper still
     * sees products and a cart. Returning 200 with this list populated is what
     * tells the SPA which widgets to render as unavailable - and what a human reads
     * at 3am when a dashboard looks subtly wrong.
     */
    public record SectionStatus(String section, boolean available, String detail) {

        public static SectionStatus ok(String section) {
            return new SectionStatus(section, true, null);
        }

        public static SectionStatus failed(String section, String detail) {
            return new SectionStatus(section, false, detail);
        }
    }

    public record ProductCard(String id, String sku, String name, String category, String brand,
                              BigDecimal price, String currency, String thumbnailUrl,
                              Double ratingAverage) {
    }

    public record CategoryCard(String name, long productCount) {
    }

    /** Who is signed in, and how long the current token has left. */
    public record SessionView(String subject, String displayName, String email, String tenantId,
                              List<String> roles, Instant accessTokenExpiresAt,
                              boolean refreshable, String loginUrl) {
    }

    /** Everything the home page needs, from a single round trip. */
    public record StorefrontView(List<ProductCard> featuredProducts,
                                 List<CategoryCard> categories,
                                 Map<String, Long> categoryCounts,
                                 List<SectionStatus> sections) {
    }

    public record CartCard(String customerId, int lineCount, int itemCount,
                           BigDecimal subtotal, String currency) {
    }

    public record OrderCard(String id, String status, BigDecimal totalAmount,
                            String currency, Instant placedAt) {
    }

    public record ProfileCard(String id, String displayName, String email,
                              String locale, String timezone, boolean marketingOptIn) {
    }

    public record RecommendationCard(String sku, String category, double score) {
    }

    /** Everything the account page needs, from a single round trip. */
    public record DashboardView(ProfileCard profile,
                                CartCard cart,
                                List<OrderCard> recentOrders,
                                List<RecommendationCard> recommendations,
                                List<String> preferredCategories,
                                String recommendationFallback,
                                List<SectionStatus> sections) {

        /** True when at least one section failed and the response is partial. */
        public boolean partial() {
            return sections.stream().anyMatch(section -> !section.available());
        }
    }
}