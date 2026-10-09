package com.enterprise.mesh.customer.domain;

/** Lifecycle state of a customer account. */
public enum CustomerStatus {
    ACTIVE,
    /** Retained for order history but barred from new activity. */
    DEACTIVATED,
    /** Under review for fraud or abuse. */
    SUSPENDED
}
