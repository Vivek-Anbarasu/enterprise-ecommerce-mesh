package com.enterprise.mesh.security;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant + security related configuration for the mesh.
 *
 * <p>Only properties that the mesh itself needs to interpret are bound here.
 * JWT verification itself is delegated to Spring Boot's standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties so that any
 * supported key source (JWKS URI, PEM public key mounted from Key Vault via the
 * CSI driver, etc.) works without custom code.
 *
 * <p>Accessors are generated. {@code @Setter} deliberately produces nothing for
 * the {@code final tenant} field - it is always present and is mutated through
 * its own setters, never replaced. {@code Tenant} is annotated separately and
 * independently; class-level annotations do not cascade to a nested class, and
 * omitting it breaks binding of {@code mesh.tenant.*} silently rather than at
 * compile time.
 */
@ConfigurationProperties(prefix = "mesh")
@Getter
@Setter
public class MeshSecurityProperties {

    /** Master switch for the mesh filter chain. */
    private boolean enabled = true;

    private final Tenant tenant = new Tenant();

    @Getter
    @Setter
    public static class Tenant {

        /** When false a request without a resolvable tenant is rejected. */
        private boolean required = true;

        /** Header consulted when the JWT carries no tenant claim. */
        private String headerName = "X-Tenant-ID";

        /** JWT claim consulted first. */
        private String claimName = "tenant_id";

        /** Default used only when {@code required=false}. */
        private String defaultTenant = "public";

        /**
         * Tenants allow-listed for this deployment; empty means allow all.
         *
         * <p>Empty also means "not configured yet", which is why a production-like
         * environment refuses to start on it — see {@code TenantAllowListSafetyCheck}.
         * Set {@link #allowAll} to assert openness deliberately.
         */
        private List<String> allowed = List.of();

        /**
         * Asserts that this deployment really should accept every tenant, so an empty
         * {@link #allowed} is a decision rather than an omission.
         *
         * <p>Deliberately not the default. "Nobody has filled this in yet" and "we
         * genuinely serve all tenants" are the same empty list, and only one of them is
         * something anybody chose.
         */
        private boolean allowAll = false;
    }
}
