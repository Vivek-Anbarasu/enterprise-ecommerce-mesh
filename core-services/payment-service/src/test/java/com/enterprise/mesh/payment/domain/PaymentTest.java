package com.enterprise.mesh.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the payment ledger's state machine.
 *
 * <p>The most valuable assertion here is that a double capture or a refund of
 * never-captured funds is impossible: both are real-world financial defects.
 */
class PaymentTest {

    private Payment payment() {
        return new Payment("pay-1", "idem-1", "tenant-1", "order-1",
                new BigDecimal("27.0000"), "USD");
    }

    @Test
    @DisplayName("a new payment starts declined/pending until the provider answers")
    void startsUnsettled() {
        assertThat(payment().getStatus()).isEqualTo(Payment.PaymentStatus.DECLINED);
    }

    @Test
    @DisplayName("the happy path DECLINED -> AUTHORISED -> CAPTURED is allowed")
    void allowsAuthoriseThenCapture() {
        Payment payment = payment();
        payment.authorise("ref-123", "Visa ****4242");
        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.AUTHORISED);

        payment.capture();
        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("capturing without a prior authorisation is rejected")
    void rejectsCaptureWithoutAuthorisation() {
        Payment payment = payment();

        assertThatThrownBy(payment::capture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot capture");
    }

    @Test
    @DisplayName("capturing twice is impossible, so a replayed webhook cannot double-charge")
    void rejectsDoubleCapture() {
        Payment payment = payment();
        payment.authorise("ref-123", "Visa ****4242");
        payment.capture();

        assertThatThrownBy(payment::capture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot capture");
    }

    @Test
    @DisplayName("refunding money that was never captured is rejected")
    void rejectsRefundBeforeCapture() {
        Payment payment = payment();
        payment.authorise("ref-123", "Visa ****4242");

        assertThatThrownBy(payment::refund)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot refund");
    }

    @Test
    @DisplayName("an authorisation can be voided instead of captured")
    void allowsVoidingAnAuthorisation() {
        Payment payment = payment();
        payment.authorise("ref-123", "Visa ****4242");
        payment.voidAuthorisation();

        assertThat(payment.getStatus()).isEqualTo(Payment.PaymentStatus.VOIDED);
    }

    @Test
    @DisplayName("a non-positive amount is rejected at construction")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new Payment("pay-2", "idem-2", "tenant-1", "order-2",
                BigDecimal.ZERO, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("the idempotency key is retained verbatim")
    void retainsIdempotencyKey() {
        assertThat(payment().getIdempotencyKey()).isEqualTo("idem-1");
    }
}
