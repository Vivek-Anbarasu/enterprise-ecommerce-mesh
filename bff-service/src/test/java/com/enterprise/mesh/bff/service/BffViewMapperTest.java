package com.enterprise.mesh.bff.service;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.enterprise.mesh.bff.api.BffViews.CartCard;
import com.enterprise.mesh.bff.api.BffViews.OrderCard;
import com.enterprise.mesh.bff.api.BffViews.ProductCard;
import com.enterprise.mesh.bff.api.BffViews.ProfileCard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-shaping layer. These tests are mostly about what happens when the
 * downstream service returns something slightly different from expectation,
 * because that is the only interesting failure mode here.
 */
class BffViewMapperTest {

    private final BffViewMapper mapper = new BffViewMapper();
    private final ObjectMapperHolder holder = new ObjectMapperHolder();

    @Test
    @DisplayName("a product page maps to cards, reading the price out of its Money object")
    void mapsProductPage() {
        JsonNode page = holder.read("""
                {
                  "content": [
                    {
                      "id": "p1", "sku": "SKU-1", "name": "Noise Cancelling Headphones",
                      "category": "audio", "brand": "Acme",
                      "basePrice": { "amount": 249.99, "currency": "USD" },
                      "thumbnailUrl": "https://cdn.test/p1.png",
                      "ratingAverage": 4.5
                    }
                  ],
                  "totalElements": 1, "page": 0, "size": 12
                }
                """);

        assertThat(mapper.productCards(page, 12))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.sku()).isEqualTo("SKU-1");
                    assertThat(card.name()).isEqualTo("Noise Cancelling Headphones");
                    assertThat(card.category()).isEqualTo("audio");
                    assertThat(card.price()).isEqualByComparingTo("249.99");
                    assertThat(card.currency()).isEqualTo("USD");
                    assertThat(card.ratingAverage()).isEqualTo(4.5);
                });
    }

    @Test
    @DisplayName("the requested page size caps how many cards are built")
    void capsProductCount() {
        JsonNode page = holder.read("""
                { "content": [
                  { "sku": "A" }, { "sku": "B" }, { "sku": "C" }, { "sku": "D" }
                ] }
                """);

        assertThat(mapper.productCards(page, 2)).hasSize(2);
    }

    @Test
    @DisplayName("a field the catalog drops yields a null card, not a failed page")
    void toleratesFieldsMissingDownstream() {
        // basePrice and ratingAverage simply are not there - the BFF must render a
        // blank price rather than take the home page down.
        JsonNode page = holder.read("""
                { "content": [ { "sku": "SKU-1", "name": "Mystery Item" } ] }
                """);

        ProductCard card = mapper.productCards(page, 12).getFirst();

        assertThat(card.sku()).isEqualTo("SKU-1");
        assertThat(card.price()).isNull();
        assertThat(card.currency()).isNull();
        assertThat(card.ratingAverage()).isNull();
    }

    @Test
    @DisplayName("an envelope the service changed shape on yields an empty list, not an exception")
    void toleratesUnrecognisedEnvelope() {
        assertThat(mapper.productCards(holder.read("[]"), 12)).isEmpty();
        assertThat(mapper.orderCards(holder.read("{}"), 5)).isEmpty();
    }

    @Test
    @DisplayName("cart lines and item counts are distinguished")
    void mapsCart() {
        JsonNode snapshot = holder.read("""
                {
                  "customerId": "user-42",
                  "lines": [
                    { "sku": "A", "quantity": 2 },
                    { "sku": "B", "quantity": 3 }
                  ],
                  "subtotal": 59.97, "currency": "USD"
                }
                """);

        CartCard card = mapper.cartCard(snapshot);

        assertThat(card.lineCount()).isEqualTo(2);
        // Two SKUs, five units: the storefront shows both numbers.
        assertThat(card.itemCount()).isEqualTo(5);
        assertThat(card.subtotal()).isEqualByComparingTo("59.97");
        assertThat(card.customerId()).isEqualTo("user-42");
    }

    @Test
    @DisplayName("an empty cart reports zero rather than failing")
    void mapsEmptyCart() {
        CartCard card = mapper.cartCard(holder.read("{ \"lines\": [], \"currency\": \"USD\" }"));

        assertThat(card.lineCount()).isZero();
        assertThat(card.itemCount()).isZero();
        assertThat(card.subtotal()).isNull();
    }

    @Test
    @DisplayName("orders are pulled out of the Spring Data page envelope")
    void mapsOrders() {
        JsonNode page = holder.read("""
                {
                  "content": [
                    { "id": "o1", "status": "PAID", "totalAmount": 120.50,
                      "currency": "USD", "createdAt": "2026-10-01T09:15:00Z" },
                    { "id": "o2", "status": "FAILED", "totalAmount": 12.00,
                      "currency": "USD", "createdAt": "2026-09-28T11:00:00Z" }
                  ],
                  "totalElements": 2
                }
                """);

        assertThat(mapper.orderCards(page, 5)).hasSize(2);
        OrderCard first = mapper.orderCards(page, 5).getFirst();
        assertThat(first.id()).isEqualTo("o1");
        assertThat(first.status()).isEqualTo("PAID");
        assertThat(first.totalAmount()).isEqualByComparingTo("120.50");
        assertThat(first.placedAt()).isNotNull();
    }

    @Test
    @DisplayName("an unparseable timestamp costs the card its date, not the whole response")
    void toleratesMalformedTimestamp() {
        JsonNode page = holder.read("""
                { "content": [ { "id": "o1", "createdAt": "not-a-date" } ] }
                """);

        OrderCard card = mapper.orderCards(page, 5).getFirst();

        assertThat(card.id()).isEqualTo("o1");
        assertThat(card.placedAt()).isNull();
    }

    @Test
    @DisplayName("profile maps, and an absent marketing flag reads as false rather than null")
    void mapsProfile() {
        JsonNode profile = holder.read("""
                { "id": "c1", "displayName": "Sam", "email": "sam@example.test",
                  "locale": "en-GB", "timezone": "Europe/London" }
                """);

        ProfileCard card = mapper.profileCard(profile);

        assertThat(card.displayName()).isEqualTo("Sam");
        assertThat(card.locale()).isEqualTo("en-GB");
        // Missing means "not opted in", which is the safe default for consent.
        assertThat(card.marketingOptIn()).isFalse();
    }

    @Test
    @DisplayName("recommendations keep the fallback marker so the row is labelled trending")
    void mapsRecommendations() {
        JsonNode response = holder.read("""
                {
                  "preferredCategories": ["audio", "wearables"],
                  "recommendations": [
                    { "sku": "SKU-9", "category": "audio", "score": 0.91 }
                  ],
                  "fallback": "trending"
                }
                """);

        assertThat(mapper.recommendationCards(response))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.sku()).isEqualTo("SKU-9");
                    assertThat(card.score()).isEqualTo(0.91);
                });
        assertThat(mapper.preferredCategories(response)).containsExactly("audio", "wearables");
        assertThat(mapper.fallbackReason(response)).isEqualTo("trending");
    }

    @Test
    @DisplayName("recommendation count is capped")
    void capsRecommendationCount() {
        JsonNode response = holder.read(
                "{ \"recommendations\": [" +
                        "{\"sku\":\"A\"},{\"sku\":\"B\"},{\"sku\":\"C\"}," +
                        "{\"sku\":\"D\"},{\"sku\":\"E\"},{\"sku\":\"F\"}," +
                        "{\"sku\":\"G\"},{\"sku\":\"H\"},{\"sku\":\"I\"},{\"sku\":\"J\"}] }");

        assertThat(mapper.recommendationCards(response))
                .hasSize(BffViewMapper.RECOMMENDATION_LIMIT);
    }

    /** Thin wrapper so the tests read as JSON literals instead of builder calls. */
    private static final class ObjectMapperHolder {

        private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

        JsonNode read(String json) {
            return mapper.readTree(json);
        }
    }
}
