package com.enterprise.mesh.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards the property this refactor exists to establish: the orchestrator does
 * not hold a transaction across a remote call, and every local write does run in
 * one.
 *
 * <p>Both halves are easy to lose in a later change. Re-annotating
 * {@code placeOrder} would silently restore the connection-and-row-lock window
 * around two HTTP calls; removing {@code @Transactional} from a state-store
 * method would make outbox writes fail (it is {@code MANDATORY}) or commit
 * partially. Neither mistake breaks a happy-path test.
 */
class SagaTransactionBoundaryTest {

    @Test
    @DisplayName("the orchestrator carries no transaction, because it makes remote calls")
    void orchestratorIsNotTransactional() {
        assertThat(CheckoutSagaOrchestrator.class.getAnnotation(Transactional.class)).isNull();

        for (Method method : CheckoutSagaOrchestrator.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("%s must not be transactional: it runs between remote calls", method.getName())
                    .isNull();
        }
    }

    @Test
    @DisplayName("each local saga write is a transaction of its own")
    void stateStoreWritesAreTransactional() throws NoSuchMethodException {
        assertTransactional("findOrCreate",
                com.enterprise.mesh.order.api.OrderDtos.PlaceOrderRequest.class,
                String.class, String.class);
        assertTransactional("claim", String.class);
        assertTransactional("current", String.class);
        assertTransactional("markStockReserved", String.class, String.class);
        assertTransactional("markPaymentAuthorised", String.class, String.class);
        assertTransactional("markStockConfirmed", String.class);
        assertTransactional("markPaymentCaptured", String.class);
        assertTransactional("markPaidAndEmit", String.class, String.class);
        assertTransactional("fail", String.class, String.class, String.class);
        assertTransactional("abandon", String.class, java.time.Instant.class);
    }

    private static void assertTransactional(String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = SagaStateStore.class.getMethod(name, parameters);
        assertThat(method.getAnnotation(Transactional.class))
                .as("SagaStateStore.%s must own its transaction", name)
                .isNotNull();
    }
}
