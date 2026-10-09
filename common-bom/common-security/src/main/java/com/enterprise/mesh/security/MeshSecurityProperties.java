package com.enterprise.mesh.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant + security related configuration for the mesh.
 *
 * <p>Only properties that the mesh itself needs to interpret are bound here.
 * JWT verification itself is delegated to Spring Boot's standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties so that any
 * supported key source (JWKS URI, PEM public key mounted from Key Vault via the
 * CSI driver, etc.) works without custom code.
 */
@ConfigurationProperties(prefix = "mesh")
public class MeshSecurityProperties {

    /** Master switch for the mesh filter chain. */
    private boolean enabled = true;

    private final Tenant tenant = new Tenant();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public static class Tenant {

        /** When false a request without a resolvable tenant is rejected. */
        private boolean required = true;

        /** Header consulted when the JWT carries no tenant claim. */
        private String headerName = "X-Tenant-ID";

        /** JWT claim consulted first. */
        private String claimName = "tenant_id";

        /** Default used only when {@code required=false}. */
        private String defaultTenant = "public";

        /** Tenants allow-listed for this deployment; empty means allow all. */
        private List<String> allowed = List.of();

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public String getClaimName() {
            return claimName;
        }

        public void setClaimName(String claimName) {
            this.claimName = claimName;
        }

        public String getDefaultTenant() {
            return defaultTenant;
        }

        public void setDefaultTenant(String defaultTenant) {
            this.defaultTenant = defaultTenant;
        }

        public List<String> getAllowed() {
            return allowed;
        }

        public void setAllowed(List<String> allowed) {
            this.allowed = allowed;
        }
    }
}
