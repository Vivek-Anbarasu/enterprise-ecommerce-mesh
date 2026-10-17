package com.enterprise.mesh.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.enterprise.mesh.ai.service.RecommendationService.Candidate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contract tests for the SKU -> category lookup.
 *
 * <p>Two things are asserted here, and the second matters more than the first. That the
 * catalogue is asked correctly is the easy half. That a catalogue failure returns null
 * rather than throwing is the part that matters: this runs from a Kafka listener that
 * has already acknowledged the event, so propagating would push a purchase signal into
 * the dead letter queue over a transient lookup failure - and a lost purchase is not
 * recoverable the way a duplicate is.
 */
class CatalogueClientTest {

    private static final String BASE = "http://catalog.test";

    private MockRestServiceServer server;
    private CatalogueClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogueClient(builder, BASE);
    }

    @Test
    @DisplayName("a product's category is read from the catalogue, under the right tenant")
    void resolvesCategory() {
        server.expect(requestTo(BASE + "/api/products/sku/SKU-1"))
                .andExpect(header("X-Tenant-ID", "acme"))
                .andRespond(withSuccess("{\"sku\":\"SKU-1\",\"category\":\"boots\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.categoryOf("SKU-1", "acme")).isEqualTo("boots");
        server.verify();
    }

    @Test
    @DisplayName("a catalogue outage yields null rather than throwing")
    void catalogueOutageYieldsNull() {
        server.expect(requestTo(BASE + "/api/products/sku/SKU-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.categoryOf("SKU-1", "acme")).isNull();
    }

    @Test
    @DisplayName("a 404 yields null, not an exception")
    void unknownSkuYieldsNull() {
        server.expect(requestTo(BASE + "/api/products/sku/SKU-NOPE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.categoryOf("SKU-NOPE", "acme")).isNull();
    }

    @Test
    @DisplayName("a blank category is treated as unresolvable rather than stored")
    void blankCategoryYieldsNull() {
        server.expect(requestTo(BASE + "/api/products/sku/SKU-2"))
                .andRespond(withSuccess("{\"sku\":\"SKU-2\",\"category\":\"  \"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.categoryOf("SKU-2", "acme")).isNull();
    }

    @Test
    @DisplayName("candidates arrive with the catalogue's category, not one guessed from the SKU")
    void candidatesCarryTheCatalogueCategory() {
        // The whole reason this method returns Candidate rather than a bare SKU: the
        // ranking rule must not re-derive a category, because a merchant filing
        // "LAPTOP-7" under "electronics" would otherwise be scored as category "LAPTOP".
        expectCategory("boots", """
                {"content":[{"sku":"B-1","category":"boots"},
                            {"sku":"B-2","category":"boots"}],
                 "totalElements":2,"page":0,"size":20}""");

        assertThat(client.candidates("acme", List.of("boots"), 20))
                .containsExactly(
                        new Candidate("B-1", "boots"),
                        new Candidate("B-2", "boots"));
        server.verify();
    }

    @Test
    @DisplayName("each requested category is asked for separately, tenant-scoped")
    void asksOncePerCategory() {
        // GET /api/products takes a single category, so there is no batch form to use.
        // This is why the controller caps how many categories it will ask about.
        expectCategory("boots", page("B-1", "boots"));
        expectCategory("coats", page("C-1", "coats"));

        assertThat(client.candidates("acme", List.of("boots", "coats"), 20))
                .extracting(Candidate::sku)
                .containsExactly("B-1", "C-1");
        server.verify();
    }

    @Test
    @DisplayName("one category failing does not discard the categories that answered")
    void partialFailureStillReturnsCandidates() {
        // A catalogue blip on one category costs a shopper a shorter shelf, not an
        // error page on the storefront's hottest read. Mirrors the asymmetry in
        // categoryOf: the catalogue is not on the purchase path.
        expectCategory("boots", page("B-1", "boots"));
        server.expect(requestTo(startsWith(BASE + "/api/products?")))
                .andExpect(queryParam("category", "coats"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.candidates("acme", List.of("boots", "coats"), 20))
                .extracting(Candidate::sku)
                .containsExactly("B-1");
        server.verify();
    }

    @Test
    @DisplayName("every category failing yields no candidates rather than an exception")
    void totalFailureYieldsNoCandidates() {
        for (String category : List.of("boots", "coats")) {
            server.expect(requestTo(startsWith(BASE + "/api/products?")))
                    .andExpect(queryParam("category", category))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThat(client.candidates("acme", List.of("boots", "coats"), 20)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("an empty category list never reaches the catalogue")
    void noCategoriesMakesNoRequest() {
        assertThat(client.candidates("acme", List.of(), 20)).isEmpty();
        assertThat(client.candidates("acme", null, 20)).isEmpty();
        server.verify();
    }

    private void expectCategory(String category, String json) {
        server.expect(requestTo(startsWith(BASE + "/api/products?")))
                .andExpect(queryParam("category", category))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "20"))
                .andExpect(header("X-Tenant-ID", "acme"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static String page(String sku, String category) {
        return "{\"content\":[{\"sku\":\"" + sku + "\",\"category\":\"" + category + "\"}],"
                + "\"totalElements\":1,\"page\":0,\"size\":20}";
    }
}