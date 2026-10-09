package com.enterprise.mesh.security.tenant;

/**
 * Holds the tenant resolved for the current request.
 *
 * <p>Backed by a {@link ThreadLocal}. Always clear it in a {@code finally}
 * block — {@link com.enterprise.mesh.security.tenant.TenantContextFilter}
 * guarantees that, but manual call sites should use
 * {@link #withTenant(String, Runnable)} or the try/finally pattern.
 */
public final class TenantContext {

    public static final String SYSTEM_TENANT = "__system__";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    /** @return the current tenant, or {@code null} when unresolved. */
    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs the given action under {@code tenantId}, restoring the previous value after. */
    public static void withTenant(String tenantId, Runnable action) {
        String previous = CURRENT.get();
        try {
            CURRENT.set(tenantId);
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
