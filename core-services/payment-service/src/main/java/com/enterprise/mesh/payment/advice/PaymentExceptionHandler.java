package com.enterprise.mesh.payment.advice;

import java.net.URI;

import com.enterprise.mesh.payment.provider.PaymentProviderUnavailableException;
import com.enterprise.mesh.payment.service.PaymentService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps payment failures to RFC 9457 problem documents.
 *
 * <p>payment-service had no advice at all, so every failure below reached the client
 * as a container 500. That is actively misleading for two of them: a payment id
 * belonging to another tenant answers the same as a genuinely unknown one, and a
 * provider timeout answers the same as a declined card. A caller retrying a
 * "declined" 500 would retry a payment that can never succeed.
 *
 * <p>The status distinction that matters commercially:
 *
 * <ul>
 *   <li>A <strong>declined card is 422</strong> and is a permanent business
 *       outcome. Retrying it does nothing, so the shopper must be told to use a
 *       different instrument rather than shown a generic error.</li>
 *   <li>A <strong>provider outage is 503</strong>. The outcome is unknown - the
 *       payment may or may not have been taken - so it must be retryable and must
 *       never be rendered as a decline.</li>
 * </ul>
 *
 * <p>Collapsing the second into the first is the failure mode worth naming: the
 * shopper is told their card was refused, no order is fulfilled, and the money is
 * nonetheless gone.
 */
@RestControllerAdvice
public class PaymentExceptionHandler {

    private static final URI BASE = URI.create("https://errors.enterprise-ecommerce.com/payment/");

    /**
     * The payment is not visible to the calling tenant.
     *
     * <p>Also raised for a well-formed id that simply does not exist, deliberately:
     * answering 404 for one and 403 for the other would confirm to a probing
     * caller that an id is real but belongs to someone else.
     */
    @ExceptionHandler(PaymentService.PaymentNotFoundException.class)
    public ProblemDetail handleNotFound(PaymentService.PaymentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Payment Not Found");
        problem.setType(URI.create(BASE + "not-found"));
        return problem;
    }

    /**
     * The provider could not be reached, or answered with something other than a
     * decision.
     *
     * <p>503 rather than 500 or 422, so the client retries with the same
     * idempotency key instead of treating the attempt as spent. The
     * {@code handle} is carried through because it is the reconciliation handle
     * for the ambiguous case, and it is a provider reference or idempotency key,
     * never a credential.
     */
    @ExceptionHandler(PaymentProviderUnavailableException.class)
    public ProblemDetail handleProviderUnavailable(PaymentProviderUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The payment provider could not be reached. The outcome is unknown, so this "
                        + "request may be retried with the same Idempotency-Key.");
        problem.setTitle("Payment Provider Unavailable");
        problem.setType(URI.create(BASE + "provider-unavailable"));
        problem.setProperty("handle", ex.getHandle());
        return problem;
    }

    /**
     * A state-machine guard rejected the transition - capturing twice, voiding a
     * capture, refunding an uncaptured payment.
     *
     * <p>409: the request was well formed and refers to a real payment, but it is
     * asking for something the ledger does not allow.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleInvalidTransition(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid Payment Transition");
        problem.setType(URI.create(BASE + "invalid-transition"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Payment Request");
        problem.setType(URI.create(BASE + "invalid-request"));
        return problem;
    }
}