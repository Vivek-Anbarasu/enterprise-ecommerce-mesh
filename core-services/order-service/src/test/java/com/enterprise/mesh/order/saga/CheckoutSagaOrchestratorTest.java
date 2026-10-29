package com.enterprise.mesh.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.enterprise.mesh.observability.BusinessMetrics;
import com.enterprise.mesh.order.api.OrderDtos.LineRequest;
import com.enterprise.mesh.order.api.OrderDtos.LineResponse;
import com.enterprise.mesh.order.api.OrderDtos.OrderResponse;
import com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest;
import com.enterprise.mesh.order.saga.SagaExceptions.InsufficientStockException;
import com.enterprise.mesh.order.saga.SagaExceptions.PaymentDeclinedException;

/**
 * Guards the saga's control flow at its boundaries.
 *
 * <p>Three properties matter more than the happy path:
 *
 * <ul>
 *   <li>an idempotent replay must not run the steps or count another outcome;</li>
 *   <li>a failure before any remote step committed must not invent compensations;</li>
 *   <li>a failure after the stock hold committed must queue the release and try it
 *       before returning.</li>
 * </ul>
 *
 * <p>The database side is mocked: these tests are about which calls are made and
 * in what order, while the transaction and step semantics live in
 * {@link SagaStateStore} and are exercised against the schema itself.
 */
class CheckoutSagaOrchestratorTest {

    private SagaStateStore stateStore;
    private InventorySagaClient inventory;
    private PaymentSagaClient payments;
    private SagaCompensationRunner compensations;
    private BusinessMetrics businessMetrics;
    private CheckoutSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        stateStore = mock(SagaStateStore.class);
        inventory = mock(InventorySagaClient.class);
        payments = mock(PaymentSagaClient.class);
        compensations = mock(SagaCompensationRunner.class);
        businessMetrics = mock(BusinessMetrics.class);

        orchestrator = new CheckoutSagaOrchestrator(
                stateStore, inventory, payments, compensations, businessMetrics, new SagaProperties());
    }

    @Test
    @DisplayName("an idempotent replay returns the original order without running the saga")
    void idempotentReplayDoesNotRunTheSaga() {
        when(stateStore.findOrCreate(any(), eq("tenant-1"), eq("corr-1")))
                .thenReturn(new SagaStateStore.Started(order("PAID"), SagaStep.COMPLETED, true));

        OrderResponse response = orchestrator.placeOrder(request(), "tenant-1", "corr-1");

        assertThat(response.status()).isEqualTo("PAID");
        verify(stateStore, never()).claim(anyString());
        verifyNoInteractions(inventory, payments, compensations, businessMetrics);
    }

    @Test
    @DisplayName("a stock rejection fails the order and queues no compensation")
    void stockRejectionQueuesNothing() {
        when(stateStore.findOrCreate(any(), eq("tenant-1"), eq("corr-1")))
                .thenReturn(new SagaStateStore.Started(order("PENDING"), SagaStep.STARTED, false));
        when(stateStore.claim("order-1")).thenReturn(true);
        doThrow(new InsufficientStockException("sold out"))
                .when(inventory).reserve(anyString(), anyString(), anyList(), any(),
                        anyString(), anyString());
        when(stateStore.fail(eq("order-1"), contains("stock unavailable"), eq("corr-1")))
                .thenReturn(new SagaStateStore.Failure(order("FAILED"), false));

        OrderResponse response = orchestrator.placeOrder(request(), "tenant-1", "corr-1");

        assertThat(response.status()).isEqualTo("FAILED");
        // Nothing committed remotely, so there is nothing to undo. Calling the
        // runner here would mean the failure path could not tell the difference.
        verify(compensations, never()).runForOrder(anyString());
        verify(businessMetrics).recordOutcome("orders", "stock_unavailable");
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("a declined payment releases the stock hold before returning")
    void declineQueuesAndRunsTheStockRelease() {
        when(stateStore.findOrCreate(any(), eq("tenant-1"), eq("corr-1")))
                .thenReturn(new SagaStateStore.Started(order("RESERVED"), SagaStep.STOCK_RESERVED, false));
        when(stateStore.claim("order-1")).thenReturn(true);
        doNothing().when(inventory).reserve(anyString(), anyString(), anyList(), any(),
                anyString(), anyString());
        when(payments.authorise(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentDeclinedException("insufficient funds"));
        when(stateStore.fail(eq("order-1"), contains("Payment declined"), eq("corr-1")))
                .thenReturn(new SagaStateStore.Failure(order("FAILED"), true));

        OrderResponse response = orchestrator.placeOrder(request(), "tenant-1", "corr-1");

        assertThat(response.status()).isEqualTo("FAILED");
        verify(compensations).runForOrder("order-1");
        verify(payments, never()).capture(anyString(), anyString());
    }

    @Test
    @DisplayName("the happy path advances every step and captures the payment once")
    void happyPathCapturesAndEmits() {
        when(stateStore.findOrCreate(any(), eq("tenant-1"), eq("corr-1")))
                .thenReturn(new SagaStateStore.Started(order("PENDING"), SagaStep.STARTED, false));
        when(stateStore.claim("order-1")).thenReturn(true);
        when(payments.authorise(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn("pay-1");
        when(stateStore.markPaidAndEmit("order-1", "corr-1")).thenReturn(order("PAID"));

        OrderResponse response = orchestrator.placeOrder(request(), "tenant-1", "corr-1");

        assertThat(response.status()).isEqualTo("PAID");
        verify(stateStore).markStockReserved("order-1", "res-order-1");
        verify(stateStore).markPaymentAuthorised("order-1", "pay-1");
        verify(stateStore).markStockConfirmed("order-1");
        verify(stateStore).markPaymentCaptured("order-1");
        verify(payments).capture("pay-1", "tenant-1");
        verify(compensations, never()).runForOrder(anyString());
        verify(businessMetrics).recordOutcome("orders", "paid");
    }

    private static PlaceOrderRequest request() {
        return new PlaceOrderRequest(
                "key-1", "customer-1",
                List.of(new LineRequest("sku-1", "Product", 1, new BigDecimal("20.0000"))),
                "1 Test Street", BigDecimal.ZERO, BigDecimal.ZERO, "token", "USD");
    }

    private static OrderResponse order(String status) {
        return new OrderResponse(
                "order-1", "key-1", "customer-1", status,
                new BigDecimal("20.0000"), "USD", BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null,
                List.of(new LineResponse("sku-1", "Product", 1,
                        new BigDecimal("20.0000"), new BigDecimal("20.0000"))),
                Instant.now(), Instant.now());
    }
}
