# ADR-0003: One schema per service, a tenant column on every aggregate

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** all services, `common-security`

## Context

The platform is multi-tenant. The same deployment serves every tenant's shoppers, and the
tenants are not equal: a Credit Officer for Corporates must not be able to see Banks model
data, even though both tenants are served by the same `order-service` pod. Isolation is a
correctness property, not a performance one — a leak is a regulatory incident, not a bug.

Three forces push in different directions:

- **Operational cost.** Every additional database or schema is another migration to run,
  another connection pool to size, and another backup to verify.
- **Blast radius.** A single missed `WHERE tenant_id = ?` is a cross-tenant read. The
  design should make that hard, and detectable.
- **Noisy neighbours.** One tenant's traffic must not starve another's.

The services already own separate databases (`orders_db`, `inventory_db`, `payments_db`,
`customers_db`, `shipping_db`), so the tenancy question is *within* each service, not
across them.

## Decision

**Shared schema per service, with `tenant_id` on every tenant-owned aggregate**, and the
tenant resolved from the authenticated token.

- **Resolution order is claim-first.** `TenantContextFilter` reads the JWT `tenant_id`
  claim. The `X-Tenant-ID` header is consulted **only when there is no validated token**;
  when the principal is a `JwtAuthenticationToken` the claim is authoritative and the
  header is not read at all. A token is never overridden by a header.
- **The resolved tenant is published in two places**: `TenantContext` (a `ThreadLocal`,
  cleared in a `finally` block) and the logging MDC, so every log line and trace is
  attributable to a tenant.
- **Allow-listing.** Requests whose resolved tenant is not on the configured allow-list are
  rejected. Production refuses to start with an insecure tenant configuration; the
  `mesh.tenant.allow-all` switch exists for tests and local runs.
- **Header rewrite for legacy call sites.** Forty-odd controllers read the header directly
  rather than through `TenantContext`. `TenantContextFilter.TenantHeaderRequestWrapper`
  rewrites the header to the resolved tenant, so those call sites cannot see a
  client-supplied value that disagrees with the token.
- **Indexes lead with `tenant_id`.** Every query index is `(tenant_id, ...)`, which is both
  the access pattern and a reminder of the filter that must be present.
- **One database per service**, so tenancy never crosses a service boundary and a
  cross-service read is an API call rather than a join.

## Consequences

### Positive

- One migration per service, one connection pool, one backup. Adding a tenant is a row,
  not an infrastructure change.
- Tenant attribution is automatic in logs and traces, which makes cross-tenant
  investigations possible.
- The header rewrite closes the obvious bypass: a caller cannot smuggle a different tenant
  past a controller that reads the header directly.

### Negative

- **Isolation is enforced in application code, not by the database.** A query that forgets
  the predicate returns another tenant's rows, and nothing at the storage layer stops it.
  This is the central risk of the decision.
- A missed filter is invisible in tests that only use one tenant, so the test suite has to
  include a two-tenant case for every read path.
- Noisy neighbours share a connection pool and a database.
- Regulatory requirements for physical separation would need a different model for those
  tenants.

### Operational

- `mesh.tenant.allow-all` must never be set in production; the safety check refuses to
  start rather than trusting the operator.
- A tenant-scoped index that does not lead with `tenant_id` is a review defect, not a
  tuning opportunity.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Database per tenant** | Migrations run N times, connection pools multiply, and cost scales with tenant count rather than traffic. Justified only for a small number of large, contractually isolated tenants. |
| **Schema per tenant** | Same migration and pool problem as database-per-tenant, with weaker isolation guarantees than a separate database and no simpler operations. |
| **PostgreSQL row-level security (RLS)** | The right *defence in depth*, but it requires a per-request `SET LOCAL` and a role model that the connection pool has to respect. Not chosen as the primary mechanism because it moves the failure mode from "forgot the filter" to "forgot to set the session variable", which is equally silent. Worth adding *alongside* the application filter. |
| **Tenant in the URL path only** | Path parameters are client-controlled. Fine for routing, unacceptable as the authorisation source. |

## Revisit triggers

- A tenant requires physical isolation for regulatory reasons.
- Tenant count or data volume makes a shared connection pool a noisy-neighbour problem.
- A cross-tenant read is found in production — at which point RLS should be added as
  defence in depth, not as a replacement for the application filter.
- The number of read paths outgrows what a two-tenant test per path can cover.

## References

- `common-bom/common-security/src/main/java/com/enterprise/mesh/security/tenant/TenantContext.java`
- `common-bom/common-security/src/main/java/com/enterprise/mesh/security/tenant/TenantContextFilter.java`
- `common-bom/common-security/src/test/java/com/enterprise/mesh/security/tenant/TenantContextFilterHeaderTest.java`
- `common-bom/common-security/src/main/java/com/enterprise/mesh/security/MeshSecurityProperties.java`
- `core-services/order-service/src/main/resources/db/migration/V1__order_schema.sql` (tenant-leading indexes)
