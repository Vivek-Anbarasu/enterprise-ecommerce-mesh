package com.enterprise.mesh.payment.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.enterprise.mesh.payment.domain.Payment;
import com.enterprise.mesh.payment.domain.PaymentService;
import com.enterprise.mesh.payment.repository.PaymentRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Authorisation, capture and refunds")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentService paymentService, PaymentRepository paymentRepository) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
    }

    public record AuthorisationRequest(
            @NotBlank @Size(max = 64) String orderId,
            @NotNull @DecimalMin(value = "0.01")
            @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 256) String paymentToken) {
    }

    public record AuthorisationResponse(String reference, boolean approved,
                                        String declineReason, Payment.PaymentStatus status) {

        static AuthorisationResponse from(Payment payment) {
            return new AuthorisationResponse(
                    payment.getId(),
                    payment.getStatus() == Payment.PaymentStatus.AUTHORISED
                            || payment.getStatus() == Payment.PaymentStatus.CAPTURED,
                    payment.getDeclineReason(),
                    payment.getStatus());
        }
    }

    public record PaymentResponse(String id, String orderId, BigDecimal amount, String currency,
                                  Payment.PaymentStatus status, String providerReference,
                                  String instrumentLabel, String declineReason,
                                  Instant createdAt) {

        static PaymentResponse from(Payment payment) {
            return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(),
                    payment.getCurrency(), payment.getStatus(), payment.getProviderReference(),
                    payment.getInstrumentLabel(), payment.getDeclineReason(), payment.getCreatedAt());
        }
    }

    /**
     * Authorises funds. Idempotent on the {@code Idempotency-Key} header, so the
     * order saga can safely retry after a timeout.
     */
    @PostMapping("/authorisations")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Authorise funds for an order (idempotent on Idempotency-Key)")
    public ResponseEntity<AuthorisationResponse> authorise(
            @Valid @RequestBody AuthorisationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        Payment payment = paymentService.authorise(idempotencyKey,
                tenantId == null ? "public" : tenantId,
                request.orderId(), request.amount(), request.currency(), request.paymentToken());

        // A decline is a successful request with an unsuccessful outcome.
        return ResponseEntity.ok(AuthorisationResponse.from(payment));
    }

    @PostMapping("/{paymentId}/capture")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Capture authorised funds")
    public ResponseEntity<PaymentResponse> capture(
            @PathVariable String paymentId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.capture(
                paymentId, tenantId == null ? "public" : tenantId)));
    }

    @PostMapping("/{paymentId}/void")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Operation(summary = "Release an authorisation without taking funds")
    public ResponseEntity<PaymentResponse> voidAuthorisation(
            @PathVariable String paymentId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.voidAuthorisation(
                paymentId, tenantId == null ? "public" : tenantId)));
    }

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    @Operation(summary = "Refund captured funds")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable String paymentId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.refund(
                paymentId, tenantId == null ? "public" : tenantId)));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Fetch one payment")
    public PaymentResponse get(@PathVariable String paymentId,
                               @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return PaymentResponse.from(paymentService.get(paymentId,
                tenantId == null ? "public" : tenantId));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Payment attempts for an order, newest first")
    public List<PaymentResponse> forOrder(
            @PathVariable String orderId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return paymentRepository
                .findByOrderIdAndTenantIdOrderByCreatedAtDesc(orderId,
                        tenantId == null ? "public" : tenantId)
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
