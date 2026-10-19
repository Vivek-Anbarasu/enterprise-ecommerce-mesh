package com.enterprise.mesh.inventoryservice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain tests for the stock ledger.
 *
 * <p>No Spring context, so these run in milliseconds and pin down the
 * availability arithmetic that overselling bugs hide in.
 */
class InventoryItemTest {

    private InventoryItem item(int onHand, int reserved, int safetyStock) {
        InventoryItem inventoryItem = new InventoryItem("id-1", "SKU-1", "tenant-1", "WH-1",
                onHand, safetyStock);
        for (int i = 0; i < reserved; i++) {
            inventoryItem.reserve(1);
        }
        return inventoryItem;
    }

    @Test
    @DisplayName("available is on-hand minus holds minus safety stock")
    void computesAvailability() {
        assertThat(item(10, 3, 2).available()).isEqualTo(5);
    }

    @Test
    @DisplayName("safety stock is never offered to shoppers")
    void neverSellsIntoSafetyStock() {
        InventoryItem inventoryItem = item(5, 0, 3);
        assertThat(inventoryItem.available()).isEqualTo(2);

        assertThatThrownBy(() -> inventoryItem.reserve(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient availability");
    }

    @Test
    @DisplayName("a reserve moves units from available into reserved without touching on-hand")
    void reserveHoldsWithoutDecrementingOnHand() {
        InventoryItem inventoryItem = item(10, 0, 0);
        inventoryItem.reserve(4);

        assertThat(inventoryItem.getOnHand()).isEqualTo(10);
        assertThat(inventoryItem.getReserved()).isEqualTo(4);
        assertThat(inventoryItem.available()).isEqualTo(6);
    }

    @Test
    @DisplayName("releasing a hold returns units to availability")
    void releaseRestoresAvailability() {
        InventoryItem inventoryItem = item(10, 4, 0);
        assertThat(inventoryItem.available()).isEqualTo(6);

        inventoryItem.release(4);

        assertThat(inventoryItem.getReserved()).isZero();
        assertThat(inventoryItem.available()).isEqualTo(10);
    }

    @Test
    @DisplayName("confirming decrements on-hand permanently")
    void confirmDecrementsOnHand() {
        InventoryItem inventoryItem = item(10, 4, 0);
        inventoryItem.confirm(4);

        assertThat(inventoryItem.getOnHand()).isEqualTo(6);
        assertThat(inventoryItem.getReserved()).isZero();
        assertThat(inventoryItem.available()).isEqualTo(6);
    }

    @Test
    @DisplayName("unconfirming reverses confirm and puts the units back on sale")
    void unconfirmReversesConfirm() {
        // Exactly the sequence an abandoned checkout leaves behind: 10 on hand,
        // 4 held, confirmed (revenue recognised), then the order fails to persist.
        InventoryItem inventoryItem = item(10, 4, 0);
        inventoryItem.confirm(4);
        assertThat(inventoryItem.getOnHand()).isEqualTo(6);

        inventoryItem.unconfirm(4);

        assertThat(inventoryItem.getOnHand()).isEqualTo(10);
        assertThat(inventoryItem.getReserved())
                .as("confirm already zeroed reserved; unconfirm must not re-add it, or the "
                        + "units would be counted as both on hand and held")
                .isZero();
        assertThat(inventoryItem.available()).isEqualTo(10);
    }

    @Test
    @DisplayName("unconfirm is rejected for a non-positive quantity")
    void unconfirmRejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> item(10, 4, 0).unconfirm(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("a double release cannot drive reserved negative")
    void doubleReleaseIsClamped() {
        InventoryItem inventoryItem = item(10, 3, 0);
        inventoryItem.release(3);
        inventoryItem.release(3);

        assertThat(inventoryItem.getReserved()).isZero();
        assertThat(inventoryItem.available()).isEqualTo(10);
    }

    @Test
    @DisplayName("receiving goods increases availability")
    void receiveIncreasesStock() {
        InventoryItem inventoryItem = item(5, 0, 0);
        inventoryItem.receive(10);

        assertThat(inventoryItem.getOnHand()).isEqualTo(15);
        assertThat(inventoryItem.available()).isEqualTo(15);
    }

    @Test
    @DisplayName("non-positive quantities are rejected")
    void rejectsNonPositiveQuantities() {
        InventoryItem inventoryItem = item(10, 0, 0);

        assertThatThrownBy(() -> inventoryItem.reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventoryItem.reserve(-5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventoryItem.receive(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the last unit can be sold, leaving nothing available")
    void allowsSellingTheLastUnit() {
        InventoryItem inventoryItem = item(1, 0, 0);
        inventoryItem.reserve(1);

        assertThat(inventoryItem.available()).isZero();
    }
}
