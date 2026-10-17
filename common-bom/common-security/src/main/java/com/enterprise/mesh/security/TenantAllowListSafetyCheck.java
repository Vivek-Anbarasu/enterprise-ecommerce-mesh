package com.enterprise.mesh.security;

import java.util.Arrays;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a production-like deployment whose tenant allow-list is empty.
 *
 * <p>{@code TenantContextFilter} rejects any tenant not on {@code mesh.tenant.allowed},
 * but only when that list is non-empty — the documented default for {@code List.of()} is
 * "allow all". Every service in the mesh shipped with the list unset, so in practice the
 * {@code 403 Tenant Not Permitted} branch was unreachable everywhere and tenant isolation
 * rested entirely on row-level partitioning in queries.
 *
 * <p>That is a defensible design for a single-tenant-per-deployment rollout, and a poor
 * accident for a mesh whose entire data model is keyed on {@code tenant_id}. The danger is
 * that "allow every tenant" and "nobody has configured this yet" are the same value, and
 * only one of them is a decision. So in a production-like environment the empty list is
 * refused at startup unless it is asserted explicitly with
 * {@code mesh.tenant.allow-all=true} — which puts the decision in values.yaml where it is
 * reviewable, instead of leaving it as an omission nobody made.
 *
 * <p>Same reasoning as {@link MeshJwtValidationSafetyCheck}: the condition shows up in no
 * metric and no log line, so refusing to start is the only reliable way to surface it.
 * Locally the check only warns, because a developer working on one service has no reason
 * to enumerate every tenant.
 *
 * <p>This is a deployment guard, not a runtime check. It does not stop a tenant that is
 * on the list from doing something it should not; that is what {@code @PreAuthorize} is
 * for. It stops the list being absent altogether, which nothing else in the stack would
 * notice.
 */
@Slf4j
@RequiredArgsConstructor
public class TenantAllowListSafetyCheck implements InitializingBean {

    /** Environments where an unenforced tenant boundary is a deployment error. */
    private static final Set<String> PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "aks-prod", "live");

    private final Environment environment;

    private final MeshSecurityProperties properties;

    @Override
    public void afterPropertiesSet() {
        var tenant = properties.getTenant();
        String meshEnv = environment.getProperty("mesh.environment", "");

        if (!tenant.getAllowed().isEmpty()) {
            log.info("Tenant allow-list active: {} tenant(s) permitted. Any other tenant is "
                    + "rejected with 403 before reaching business logic.", tenant.getAllowed().size());
            return;
        }

        if (tenant.isAllowAll()) {
            log.warn("Tenant allow-list disabled (mesh.tenant.allow-all=true) in environment '{}'. "
                    + "Any tenant may address this deployment; isolation rests on row-level "
                    + "tenant filtering in queries alone.", meshEnv);
            return;
        }

        if (isProduction(meshEnv)) {
            throw new IllegalStateException(
                    "mesh.tenant.allowed is empty in environment '" + meshEnv + "', so this "
                            + "service accepts requests from every tenant. Tenant isolation then "
                            + "rests entirely on row-level filtering in queries, and a missing "
                            + "predicate is silently a cross-tenant read. List the tenants this "
                            + "deployment serves (--set global.mesh.tenantAllowed={acme,globex}), "
                            + "or, if this deployment genuinely is open to all tenants, say so "
                            + "explicitly with mesh.tenant.allow-all=true so the decision is "
                            + "reviewable rather than an omission.");
        }

        log.warn("No tenant allow-list configured (environment '{}'); every tenant is permitted. "
                + "Expected locally. In a deployed environment this service will refuse to start "
                + "unless the list is set or mesh.tenant.allow-all=true.", meshEnv);
    }

    /**
     * Treats an environment as production when it says so, or when it is unrecognised
     * and a production profile is active. An unknown value under a production profile
     * is treated as production, so a typo fails closed.
     */
    private boolean isProduction(String meshEnv) {
        if (meshEnv != null && PRODUCTION_ENVIRONMENTS.contains(meshEnv.toLowerCase())) {
            return true;
        }
        if (meshEnv == null || meshEnv.isBlank()) {
            return Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(profile -> PRODUCTION_ENVIRONMENTS.contains(profile.toLowerCase()));
        }
        return false;
    }
}