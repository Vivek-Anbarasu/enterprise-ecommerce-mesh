package com.enterprise.mesh.order.saga;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.enterprise.mesh.observability.BusinessMetrics;
import com.enterprise.mesh.order.api.OrderDtos.LineRequest;
import com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest;
import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.persistence.OrderRepository;

/**
 * Guards the business-event side of checkout.
 *
 * <p>{@code CheckoutConversionCollapse} divides paid orders by all order
 * outcomes. If an idempotent retry counted as another paid order, a client that
 * retries after a timeout would make a broken checkout look healthier than it
 * is. The replay path must therefore return the original order without
 * touching the counter.
 */
class CheckoutSagaOrchestratorTest {

    private OrderRepository orderRepository;
    private BusinessMetrics businessMetrics;
    private CheckoutSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        when(restClientBuilder.build()).thenReturn(mock(RestClient.class));
        businessMetrics = mock(BusinessMetrics.class);
        orchestrator = new CheckoutSagaOrchestrator(
                orderRepository, outboxService, restClientBuilder, businessMetrics, 900);
    }

    @Test
    @DisplayName("an idempotent replay returns the original order without counting another checkout")
    void idempotentReplayDoesNotRecordAnOutcome() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                "key-1", "customer-1",
                List.of(new LineRequest("sku-1", "Product", 1, BigDecimal.ONE)),
                "1 Test Street", BigDecimal.ZERO, BigDecimal.ZERO, "token", "USD");

        Order existing = mock(Order.class);
        when(existing.getId()).thenReturn("order-1");
        when(existing.getIdempotencyKey()).thenReturn("key-1");
        when(existing.getCustomerId()).thenReturn("customer-1");
        when(existing.getStatus()).thenReturn(Order.OrderStatus.PAID);
        when(existing.getLines()).thenReturn(List.of());
        when(orderRepository.findByIdempotencyKeyAndTenantId("key-1", "tenant-1"))
                .thenReturn(Optional.of(existing));

        orchestrator.placeOrder(request, "tenant-1", "corr-1");

        verifyNoInteractions(businessMetrics);
    }
}
