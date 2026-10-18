package com.enterprise.mesh.payment.domain;

/**
 * Outcome of a provider call.
 *
 * <p>Lives beside {@link PaymentGateway} rather than inside the service that consumes
 * it, so the port and the type it speaks do not depend on the layer above them.
 */
public record GatewayResult(boolean approved, String providerReference,
                            String instrumentLabel, String declineReason) {

    public static GatewayResult approved(String reference, String label) {
        return new GatewayResult(true, reference, label, null);
    }

    public static GatewayResult declined(String reason) {
        return new GatewayResult(false, null, null, reason);
    }
}