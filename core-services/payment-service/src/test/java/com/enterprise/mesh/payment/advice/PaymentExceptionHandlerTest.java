package com.enterprise.mesh.payment.advice;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.enterprise.mesh.payment.domain.Payment;
import com.enterprise.mesh.payment.provider.PaymentProviderUnavailableException;
import com.enterprise.mesh.payment.service.PaymentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Guards the status codes payment-service reports.
 *
 * <p>This module had no advice at all, so all of these reached the client as a
 * container 500. Two of them were actively harmful that way: a declined card and
 * a provider outage were indistinguishable, and a caller retrying a "declined" 500
 * would retry a payment that can never succeed.
 */
class PaymentExceptionHandlerTest {

    private final PaymentExceptionHandler handler = new PaymentExceptionHandler();

    private PaymentService.PaymentNotFoundException notFound() {
        Payment payment = new Payment("pay-1", "idem-1", "tenant-1", "order-1",
                new BigDecimal("10.0000"), "USD");
        return new PaymentService.PaymentNotFoundException(payment.getId());
    }

    @Test
    @DisplayName("a payment belonging to another tenant is 404, not 403")
    void crossTenantIsNotFound() {
        ProblemDetail problem = handler.handleNotFound(notFound());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Payment Not Found");
        // Answering 403 here would confirm to a probing caller that the id is real
        // but belongs to somebody else.
        assertThat(problem.getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getType().toString()).endsWith("/payment/not-found");
    }

    @Test
    @DisplayName("a provider outage is 503 and retryable, never a decline")
    void providerOutageIsRetryable() {
        ProblemDetail problem = handler.handleProviderUnavailable(
                new PaymentProviderUnavailableException("gateway timeout", "idem-1"));

        assertThat(problem.getStatus())
                .as("the outcome is unknown, so it must be distinguishable from a decline "
                        + "and retryable with the same Idempotency-Key")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getProperties())
                .as("the handle is the reconciliation key for the ambiguous case")
                .containsEntry("handle", "idem-1");
        assertThat(problem.getDetail()).contains("may be retried");
    }

    @Test
    @DisplayName("a rejected ledger transition is 409, not 500")
    void invalidTransitionIsConflict() {
        ProblemDetail problem = handler.handleInvalidTransition(
                new IllegalStateException("Cannot capture a payment in state CAPTURED (pay-1)"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid Payment Transition");
        assertThat(problem.getType().toString()).endsWith("/payment/invalid-transition");
    }

    @Test
    @DisplayName("a malformed request is 400")
    void badRequestIsBadRequest() {
        ProblemDetail problem = handler.handleBadRequest(
                new IllegalArgumentException("Payment amount must be positive"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Payment amount must be positive");
    }

    @Test
    @DisplayName("every payment problem type is distinct")
    void everyTypeIsDistinct() {
        // Guards against copy-paste that gives two failures the same type URI, which
        // is how a client ends up treating an outage as a decline.
        var types = java.util.Set.of(
                handler.handleNotFound(notFound()).getType().toString(),
                handler.handleProviderUnavailable(
                        new PaymentProviderUnavailableException("t", "h")).getType().toString(),
                handler.handleInvalidTransition(new IllegalStateException("x")).getType().toString(),
                handler.handleBadRequest(new IllegalArgumentException("x")).getType().toString());

        assertThat(types).hasSize(4).allSatisfy(t -> assertThat(t).startsWith(
                "https://errors.enterprise-ecommerce.com/payment/"));
    }
}