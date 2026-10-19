package com.enterprise.mesh.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.enterprise.mesh.payment.domain.Payment;
import com.enterprise.mesh.payment.domain.PaymentGateway;
import com.enterprise.mesh.payment.provider.PaymentProviderUnavailableException;
import com.enterprise.mesh.payment.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Guards what {@code capture} does to the provider and to the ledger.
 *
 * <p>Until this path existed, {@code capture} only ever touched the database: the
 * {@link PaymentGateway} port had no capture method, so a capture recorded
 * revenue recognition against money no provider had taken, and no test could have
 * caught it because nothing asserted the gateway was reached at all.
 *
 * <p>Ordering is the property worth defending. Taking the funds before recording
 * the capture is what makes a failure recoverable: the row stays AUTHORISED and a
 * retry re-sends the same idempotency key. Flipping the row first is the
 * reverse, and it produces revenue that was never collected.
 */
class PaymentServiceCaptureTest {

    private PaymentRepository repository;
    private PaymentGateway gateway;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        service = new PaymentService(repository, gateway);
    }

    private Payment authorised() {
        Payment payment = new Payment("pay-1", "idem-1", "tenant-1", "order-1",
                new BigDecimal("129.9900"), "GBP");
        payment.authorise("pi_123", "Visa ****4242");
        when(repository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(repository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        return payment;
    }

    @Test
    @DisplayName("capture reaches the provider, and the provider is called before the row is saved")
    void captureCallsProviderBeforeSaving() {
        Payment payment = authorised();

        Payment result = service.capture("pay-1", "tenant-1");

        verify(gateway).capture("pi_123", payment.getAmount(), "GBP", "idem-1:capture");
        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.CAPTURED);

        InOrder order = inOrder(gateway, repository);
        order.verify(gateway).capture(any(), any(), any(), any());
        order.verify(repository).save(any(Payment.class));
    }

    @Test
    @DisplayName("the capture idempotency key is derived, not the authorisation's own key")
    void idempotencyKeyIsDerived() {
        Payment payment = authorised();

        service.capture("pay-1", "tenant-1");

        // Reusing "idem-1" would make the provider treat the capture as a replay of
        // the authorisation and return its stored response without taking money.
        verify(gateway).capture(any(), any(), any(), org.mockito.ArgumentMatchers.eq("idem-1:capture"));
        assertThat(payment.getIdempotencyKey()).isEqualTo("idem-1");
    }

    @Test
    @DisplayName("when the provider capture fails, no revenue is recorded")
    void providerFailureLeavesThePaymentAuthorised() {
        Payment payment = authorised();
        org.mockito.Mockito.doThrow(new PaymentProviderUnavailableException("timeout", "idem-1:capture"))
                .when(gateway).capture(any(), any(), any(), any());

        assertThatThrownBy(() -> service.capture("pay-1", "tenant-1"))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        assertThat(payment.getStatus())
                .as("recording CAPTURED after a failed provider call would report revenue "
                        + "recognised for money that was never taken")
                .isEqualTo(Payment.PaymentStatus.AUTHORISED);
        verify(repository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("an already-captured payment is a local no-op and never reaches the provider")
    void alreadyCapturedDoesNotCallTheProvider() {
        Payment payment = authorised();
        payment.capture();

        Payment result = service.capture("pay-1", "tenant-1");

        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.CAPTURED);
        verify(gateway, never()).capture(any(), any(), any(), any());
    }
}