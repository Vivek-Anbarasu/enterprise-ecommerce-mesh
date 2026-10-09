package com.enterprise.mesh.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the checkout state machine.
 *
 * <p>The transition table is the contract the saga depends on: an illegal move
 * must fail loudly rather than silently corrupt an order's lifecycle.
 */
class OrderTest {

    private Order order() {
        Order order = new Order("o-1", "idem-1", "tenant-1", "cust-1",
                "1 Test Street, Testville", "corr-1");
        order.addLine("SKU-1", "Widget", 2, new BigDecimal("10.0000"),
                new BigDecimal("20.0000"));
        return order;
    }

    @Test
    @DisplayName("line totals and the order total accumulate correctly")
    void accumulatesTotals() {
        Order order = order();
        order.recalculateTotals(new BigDecimal("2.0000"), new BigDecimal("5.0000"));

        // 20.00 subtotal + 2.00 tax + 5.00 shipping
        assertThat(order.getTotalAmount()).isEqualByComparingTo("27.0000");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("2.0000");
        assertThat(order.getShippingAmount()).isEqualByComparingTo("5.0000");
    }

    @Test
    @DisplayName("the happy path PENDING -> RESERVED -> PAID is allowed")
    void allowsHappyPath() {
        Order order = order();
        order.transitionTo(Order.OrderStatus.RESERVED);
        order.transitionTo(Order.OrderStatus.PAID);

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PAID);
    }

    @Test
    @DisplayName("a pending order may be cancelled")
    void allowsCancellationFromPending() {
        Order order = order();
        order.transitionTo(Order.OrderStatus.CANCELLED);

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("PENDING cannot jump straight to PAID")
    void rejectsSkippingReservation() {
        Order order = order();

        assertThatThrownBy(() -> order.transitionTo(Order.OrderStatus.PAID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal order transition");
    }

    @Test
    @DisplayName("a delivered order is terminal")
    void rejectsTransitionsAfterDelivery() {
        Order order = order();
        order.transitionTo(Order.OrderStatus.RESERVED);
        order.transitionTo(Order.OrderStatus.PAID);
        order.transitionTo(Order.OrderStatus.SHIPPED);
        order.transitionTo(Order.OrderStatus.DELIVERED);

        assertThatThrownBy(() -> order.transitionTo(Order.OrderStatus.REFUNDED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("moving to the current status is a no-op, not an error")
    void allowsSelfTransition() {
        Order order = order();
        order.transitionTo(Order.OrderStatus.PENDING);

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PENDING);
    }

    @Test
    @DisplayName("a non-positive line quantity is rejected")
    void rejectsInvalidLineQuantity() {
        Order order = order();

        assertThatThrownBy(() -> order.addLine("SKU-2", "Gadget", 0,
                BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("marking failed records a truncated reason")
    void recordsFailureReason() {
        Order order = order();
        order.markFailed("payment provider timed out");

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.FAILED);
        assertThat(order.getFailureReason()).isEqualTo("payment provider timed out");
    }
}
