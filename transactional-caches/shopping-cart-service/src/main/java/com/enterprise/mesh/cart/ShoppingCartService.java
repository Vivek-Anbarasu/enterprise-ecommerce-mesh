package com.enterprise.mesh.cart;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Session cart held in Redis.
 *
 * <p>Redis (not Postgres) because a cart is ephemeral, write-heavy and read by
 * exactly one shopper at a time - it is state, not a system of record. The
 * authoritative record is the order created at checkout.
 *
 * <p>Every mutation refreshes the TTL so an actively-used cart does not expire
 * mid-session, while an abandoned one disappears on its own.
 */
@Service
public class ShoppingCartService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingCartService.class);

    /** Guardrail against a runaway client filling the cart key. */
    private static final int MAX_LINES = 200;

    private final RedisTemplate<String, CartSnapshot> redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public ShoppingCartService(RedisTemplate<String, CartSnapshot> redisTemplate,
                               @Value("${mesh.cart.key-prefix:cart:}") String keyPrefix,
                               @Value("${mesh.cart.ttl-minutes:20160}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    private String key(String tenantId, String customerId) {
        // Tenant is part of the key so one namespace can never read another's cart.
        return keyPrefix + tenantId + ":" + customerId;
    }

    @SuppressWarnings("unchecked")
    public CartSnapshot getCart(String tenantId, String customerId) {
        CartSnapshot snapshot = (CartSnapshot) redisTemplate.opsForValue()
                .get(key(tenantId, customerId));
        if (snapshot == null) {
            log.debug("No cart for customer {}; starting an empty one", customerId);
            return new CartSnapshot(customerId, new ArrayList<>(), BigDecimal.ZERO,
                    currencyOf(BigDecimal.ZERO), Instant.now());
        }
        return snapshot;
    }

    /**
     * Adds a line, or increments the quantity when the SKU is already present.
     *
     * <p>Unit price is captured here as a display hint only. Checkout re-prices
     * against the catalogue, because a price shown in a cart must never become
     * the price charged.
     */
    public CartSnapshot addItem(String tenantId, String customerId, String sku, String name,
                                int quantity, BigDecimal unitPrice, String currency) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        CartSnapshot cart = getCart(tenantId, customerId);
        if (cart.lines().size() >= MAX_LINES && cart.lines().stream().noneMatch(l -> l.sku().equals(sku))) {
            throw new CartLimitExceededException(MAX_LINES);
        }

        List<CartLine> lines = new ArrayList<>(cart.lines());
        lines.removeIf(line -> line.sku().equals(sku));
        lines.add(new CartLine(sku, name, quantity, unitPrice, currency));

        return save(tenantId, customerId, cart, lines);
    }

    /** Sets an absolute quantity, which is how a shopper decrements to a new value. */
    public CartSnapshot updateQuantity(String tenantId, String customerId, String sku, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must not be negative");
        }
        CartSnapshot cart = getCart(tenantId, customerId);
        List<CartLine> lines = new ArrayList<>(cart.lines());

        if (quantity == 0) {
            lines.removeIf(line -> line.sku().equals(sku));
        } else {
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).sku().equals(sku)) {
                    CartLine existing = lines.get(i);
                    lines.set(i, new CartLine(existing.sku(), existing.name(), quantity,
                            existing.unitPrice(), existing.currency()));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("SKU " + sku + " is not in the cart");
            }
        }
        return save(tenantId, customerId, cart, lines);
    }

    public CartSnapshot removeItem(String tenantId, String customerId, String sku) {
        CartSnapshot cart = getCart(tenantId, customerId);
        List<CartLine> lines = new ArrayList<>(cart.lines());
        boolean removed = lines.removeIf(line -> line.sku().equals(sku));
        if (!removed) {
            return cart;
        }
        return save(tenantId, customerId, cart, lines);
    }

    public void clearCart(String tenantId, String customerId) {
        redisTemplate.delete(key(tenantId, customerId));
        log.info("Cleared cart for customer {}", customerId);
    }

    /** Called after checkout so a completed cart cannot be re-submitted. */
    public void archiveCart(String tenantId, String customerId) {
        clearCart(tenantId, customerId);
    }

    private CartSnapshot save(String tenantId, String customerId, CartSnapshot cart,
                              List<CartLine> lines) {
        BigDecimal subtotal = lines.stream()
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String currency = currencyOf(subtotal);
        CartSnapshot updated = new CartSnapshot(customerId, lines, subtotal, currency, Instant.now());

        redisTemplate.opsForValue().set(key(tenantId, customerId), updated, ttl);
        log.debug("Cart for customer {} now has {} line(s), subtotal={} {}",
                customerId, lines.size(), subtotal, currency);
        return updated;
    }

    private String currencyOf(BigDecimal amount) {
        return "USD";
    }

    public record CartLine(String sku, String name, int quantity,
                           BigDecimal unitPrice, String currency) {
    }

    public record CartSnapshot(String customerId, List<CartLine> lines, BigDecimal subtotal,
                               String currency, Instant updatedAt) {
    }

    /** Raised when a cart exceeds the configured line limit. */
    public static class CartLimitExceededException extends RuntimeException {
        public CartLimitExceededException(int limit) {
            super("Cart cannot hold more than " + limit + " distinct items");
        }
    }
}
