package com.enterprise.mesh.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;

import com.enterprise.mesh.payment.domain.PaymentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contract tests for the HTTP provider gateway.
 *
 * <p>No network and no Spring context: {@link MockRestServiceServer} is bound to the
 * same {@link RestClient.Builder} the gateway builds from, so these assertions are about
 * the bytes actually on the wire — which is the only place the properties that matter
 * (the idempotency key, the auth header, the decline-versus-error split) are
 * observable at all.
 */
class HttpPaymentGatewayTest {

    private static final String AUTHORISATIONS = "https://payments.example/payments/authorisations";
    private static final String REFUNDS = "https://payments.example/payments/refunds";
    private static final BigDecimal AMOUNT = new BigDecimal("129.99");
    private static final String CURRENCY = "GBP";
    private static final String TOKEN = "tok_live_abc123";
    private static final String API_KEY = "sk_test_secret_value";

    private static final String APPROVED_BODY = """
            {"status":"authorised","providerReference":"pi_123","instrumentLabel":"Visa ****4242"}
            """;

    private MockRestServiceServer server;
    private HttpPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        PaymentProviderProperties properties = new PaymentProviderProperties();
        properties.setBaseUrl("https://payments.example");
        properties.setApiKey(API_KEY);

        gateway = new HttpPaymentGateway(builder, properties);
    }

    @Test
    @DisplayName("an approval maps to an approved result carrying the provider reference")
    void approvalMaps() {
        server.expect(requestTo(AUTHORISATIONS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().string(containsString("129.99")))
                .andExpect(content().string(containsString("GBP")))
                .andRespond(withSuccess(APPROVED_BODY, MediaType.APPLICATION_JSON));

        PaymentService.GatewayResult result = gateway.authorise(TOKEN, AMOUNT, CURRENCY, "idem-1");

        assertThat(result.approved()).isTrue();
        assertThat(result.providerReference()).isEqualTo("pi_123");
        assertThat(result.instrumentLabel()).isEqualTo("Visa ****4242");
        assertThat(result.declineReason()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("the idempotency key is forwarded byte-for-byte")
    void idempotencyKeyForwardedUnchanged() {
        // The anti-double-charge property. PaymentService replays a stored outcome for a
        // repeated key, which only works if the provider saw the same key and so
        // recognised the retry as the same charge rather than a second one.
        server.expect(requestTo(AUTHORISATIONS))
                .andExpect(header("Idempotency-Key", "idem-key-ORDER-9-ATTEMPT-1"))
                .andRespond(withSuccess(APPROVED_BODY, MediaType.APPLICATION_JSON));

        gateway.authorise(TOKEN, AMOUNT, CURRENCY, "idem-key-ORDER-9-ATTEMPT-1");

        server.verify();
    }

    @Test
    @DisplayName("a decline is a business outcome, returned rather than thrown")
    void declineIsAnOutcome() {
        server.expect(requestTo(AUTHORISATIONS))
                .andRespond(withSuccess("""
                        {"status":"declined","declineReason":"insufficient_funds"}
                        """, MediaType.APPLICATION_JSON));

        PaymentService.GatewayResult result = gateway.authorise(TOKEN, AMOUNT, CURRENCY, "k");

        assertThat(result.approved()).isFalse();
        assertThat(result.declineReason()).isEqualTo("insufficient_funds");
    }

    @Test
    @DisplayName("a 502 throws rather than reporting a decline")
    void serverErrorIsNotADecline() {
        // The distinction that matters most here. A decline is persisted as a decision
        // the provider made; a 502 means the outcome is unknown. Reporting it as a
        // decline would tell the shopper their card failed while the money was
        // possibly taken.
        server.expect(requestTo(AUTHORISATIONS))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.authorise(TOKEN, AMOUNT, CURRENCY, "idem-2"))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessageContaining("502")
                .hasMessageContaining("idem-2");
    }

    @Test
    @DisplayName("a 401 does not leak the credential or the token into the exception")
    void authFailureDoesNotLeakKey() {
        server.expect(requestTo(AUTHORISATIONS))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> gateway.authorise(TOKEN, AMOUNT, CURRENCY, "k"))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain(API_KEY);
                    assertThat(ex.getMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    @DisplayName("a transport failure is not reported as a decline")
    void transportFailureIsNotADecline() {
        server.expect(requestTo(AUTHORISATIONS))
                .andRespond(request -> {
                    throw new IOException("connection reset by peer");
                });

        assertThatThrownBy(() -> gateway.authorise(TOKEN, AMOUNT, CURRENCY, "k"))
                .isNotInstanceOf(PaymentService.GatewayResult.class)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an empty 200 body is unknown, not an approval")
    void emptyBodyIsUnknown() {
        server.expect(requestTo(AUTHORISATIONS))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.authorise(TOKEN, AMOUNT, CURRENCY, "k"))
                .isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    @DisplayName("refund posts the reference and accepts 204")
    void refundSucceeds() {
        server.expect(requestTo(REFUNDS))
                .andExpect(content().string(containsString("pi_123")))
                .andRespond(withNoContent());

        gateway.refund("pi_123", AMOUNT, CURRENCY);

        server.verify();
    }

    @Test
    @DisplayName("a failed refund throws rather than silently reporting success")
    void refundFailureThrows() {
        server.expect(requestTo(REFUNDS))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> gateway.refund("pi_123", AMOUNT, CURRENCY))
                .isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    @DisplayName("the gateway refuses to construct without an api key")
    void refusesWithoutApiKey() {
        PaymentProviderProperties properties = new PaymentProviderProperties();
        properties.setBaseUrl("https://payments.example");

        assertThatThrownBy(() -> new HttpPaymentGateway(RestClient.builder(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    @DisplayName("the gateway refuses to construct without a base url")
    void refusesWithoutBaseUrl() {
        PaymentProviderProperties properties = new PaymentProviderProperties();
        properties.setApiKey(API_KEY);

        assertThatThrownBy(() -> new HttpPaymentGateway(RestClient.builder(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }
}