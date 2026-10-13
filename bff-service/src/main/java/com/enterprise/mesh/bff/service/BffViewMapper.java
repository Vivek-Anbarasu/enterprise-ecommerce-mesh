package com.enterprise.mesh.bff.service;

import tools.jackson.databind.JsonNode;

import com.enterprise.mesh.bff.api.BffViews.CartCard;
import com.enterprise.mesh.bff.api.BffViews.OrderCard;
import com.enterprise.mesh.bff.api.BffViews.ProductCard;
import com.enterprise.mesh.bff.api.BffViews.ProfileCard;
import com.enterprise.mesh.bff.api.BffViews.RecommendationCard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Re-shapes gateway payloads into the storefront's view types.
 *
 * <p>Every read goes through {@link JsonNode#path} rather than a typed binding, and
 * every conversion tolerates absence. That is the difference between a BFF and a
 * fan-out proxy: the catalog service is free to rename {@code ratingAverage} or turn
 * {@code basePrice} into a list, and the home page keeps rendering with the affected
 * field blank instead of failing with a deserialisation error that takes the cart
 * widget down with it.
 *
 * <p>The cost is that a typo in a field name here fails silently to blank. Each
 * accessor is therefore named for what it reads, and the tests pin the interesting
 * shapes - including a field that is simply not there.
 */
@Component
public class BffViewMapper {

    /** How many products the storefront page leads with. */
    public static final int FEATURED_LIMIT = 12;

    /** How many orders the account page shows. */
    public static final int RECENT_ORDER_LIMIT = 5;

    public static final int RECOMMENDATION_LIMIT = 8;

    /**
     * Pulls the product page out of {@code ProductPage{content, totalElements, ...}}.
     *
     * @param size how many products to ask the catalog for
     */
    public List<ProductCard> productCards(JsonNode page, int size) {
        List<ProductCard> cards = new ArrayList<>();
        JsonNode content = page.path("content");
        if (!content.isArray()) {
            return cards;
        }
        for (JsonNode product : content) {
            if (cards.size() >= size) {
                break;
            }
            cards.add(productCard(product));
        }
        return cards;
    }

    public ProductCard productCard(JsonNode product) {
        // Price is a Money object on the catalog document, not a bare number.
        JsonNode basePrice = product.path("basePrice");
        return new ProductCard(
                text(product, "id"),
                text(product, "sku"),
                text(product, "name"),
                text(product, "category"),
                text(product, "brand"),
                decimal(basePrice, "amount"),
                text(basePrice, "currency"),
                text(product, "thumbnailUrl"),
                decimalValue(product, "ratingAverage"));
    }

    public CartCard cartCard(JsonNode snapshot) {
        JsonNode lines = snapshot.path("lines");
        int lineCount = lines.isArray() ? lines.size() : 0;
        int itemCount = 0;
        if (lines.isArray()) {
            for (JsonNode line : lines) {
                itemCount += line.path("quantity").intValue(0);
            }
        }
        return new CartCard(
                text(snapshot, "customerId"),
                lineCount,
                itemCount,
                decimal(snapshot, "subtotal"),
                text(snapshot, "currency"));
    }

    public OrderCard orderCard(JsonNode order) {
        return new OrderCard(
                text(order, "id"),
                text(order, "status"),
                decimal(order, "totalAmount"),
                text(order, "currency"),
                instant(order, "createdAt"));
    }

    /**
     * Pulls orders out of a Spring Data {@code Page} serialisation.
     *
     * <p>{@code Page} renders as {@code {content: [...], totalElements, ...}}, the
     * same envelope as {@code ProductPage}, but they are unrelated types and this
     * one is only ever asked for the first handful of rows.
     */
    public List<OrderCard> orderCards(JsonNode page, int size) {
        List<OrderCard> cards = new ArrayList<>();
        JsonNode content = page.path("content");
        if (!content.isArray()) {
            return cards;
        }
        for (JsonNode order : content) {
            if (cards.size() >= size) {
                break;
            }
            cards.add(orderCard(order));
        }
        return cards;
    }

    public ProfileCard profileCard(JsonNode profile) {
        return new ProfileCard(
                text(profile, "id"),
                text(profile, "displayName"),
                text(profile, "email"),
                text(profile, "locale"),
                text(profile, "timezone"),
                profile.path("marketingOptIn").booleanValue(false));
    }

    public List<RecommendationCard> recommendationCards(JsonNode response) {
        List<RecommendationCard> cards = new ArrayList<>();
        JsonNode recommendations = response.path("recommendations");
        if (!recommendations.isArray()) {
            return cards;
        }
        for (JsonNode scored : recommendations) {
            if (cards.size() >= RECOMMENDATION_LIMIT) {
                break;
            }
            cards.add(new RecommendationCard(
                    text(scored, "sku"),
                    text(scored, "category"),
                    scored.path("score").asDouble(0d)));
        }
        return cards;
    }

    public List<String> preferredCategories(JsonNode response) {
        List<String> categories = new ArrayList<>();
        JsonNode preferred = response.path("preferredCategories");
        if (!preferred.isArray()) {
            return categories;
        }
        for (JsonNode category : preferred) {
            String value = category.asString();
            if (value != null && !value.isBlank()) {
                categories.add(value);
            }
        }
        return categories;
    }

    /**
     * The recommender's own "not enough history yet" marker, passed straight
     * through so the SPA can label the row as trending rather than personalised.
     */
    public String fallbackReason(JsonNode response) {
        return text(response, "fallback");
    }

    // ---------------------------------------------------------------- accessors

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.decimalValue();
    }

    private static Double decimalValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        double asDouble = value.asDouble();
        return Double.isNaN(asDouble) ? null : asDouble;
    }

    /**
     * Parses an ISO-8601 instant, returning null rather than throwing.
     *
     * <p>A malformed timestamp in one order is not worth failing the whole account
     * page over - the card just renders without a date.
     */
    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException | NullPointerException ex) {
            return null;
        }
    }
}