package com.enterprise.mesh.bff.client;

import tools.jackson.databind.JsonNode;

import com.enterprise.mesh.bff.session.SessionTokens;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The BFF's only way into the business mesh: through the {@code api-gateway}.
 *
 * <p>There is deliberately no direct client for any individual service. Routing
 * everything through the gateway means tenant re-derivation from the validated
 * token, per-route rate limiting and per-route circuit breaking all continue to
 * happen in exactly one place. A BFF that called services directly would quietly
 * become a second edge, and would be the one path into the mesh that none of those
 * controls applied to.
 *
 * <p>Responses stay as {@link JsonNode}. The gateway is a pass-through, so what
 * arrives here is whatever the target service chose to serialise, and the BFF's job
 * is to re-shape that for the SPA rather than to re-declare it here - a typed mirror
 * of every downstream DTO would drift the first time a service added a field.
 */
@Component
public class MeshApiClient {

    private static final Logger log = LoggerFactory.getLogger(MeshApiClient.class);

    public static final String TENANT_HEADER = "X-Tenant-ID";

    private final RestClient restClient;

    public MeshApiClient(RestClient meshRestClient) {
        this.restClient = meshRestClient;
    }

    /** Anonymous call, for surfaces the mesh serves without a principal. */
    public JsonNode get(String path) {
        return exchange(path, null);
    }

    /**
     * Authenticated call. The bearer token goes out as a header and is never
     * reflected back to the caller.
     */
    public JsonNode get(String path, SessionTokens tokens) {
        return exchange(path, tokens);
    }

    private JsonNode exchange(String path, SessionTokens tokens) {
        try {
            var request = restClient.get().uri(path);
            if (tokens != null) {
                request.header(HttpHeaders.AUTHORIZATION, bearer(tokens));
                // Traceability only. The gateway deletes any inbound X-Tenant-ID and
                // re-derives it from the validated access token, so this cannot be
                // used to claim another tenant - see TenantForwardingFilter.
                if (tokens.tenantId() != null) {
                    request.header(TENANT_HEADER, tokens.tenantId());
                }
            }
            return request.retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.Unauthorized ex) {
            throw new DownstreamUnauthorizedException(path, ex);
        } catch (HttpClientErrorException.NotFound ex) {
            throw DownstreamException.from(HttpStatus.NOT_FOUND, path, "The requested resource does not exist");
        } catch (HttpClientErrorException.Forbidden ex) {
            throw DownstreamException.from(HttpStatus.FORBIDDEN, path,
                    "You do not have permission to view this resource");
        } catch (HttpClientErrorException ex) {
            log.warn("Downstream rejected request path={} status={}", path, ex.getStatusCode().value());
            throw DownstreamException.from(ex.getStatusCode(), path, "The request was rejected downstream");
        } catch (RestClientResponseException ex) {
            log.warn("Downstream failed path={} status={}", path, ex.getStatusCode().value());
            throw DownstreamException.from(ex.getStatusCode(), path, "The downstream service is unavailable");
        } catch (ResourceAccessException ex) {
            // Connect timeout, read timeout, DNS, connection refused. All of them
            // are "the gateway did not answer", and all of them are retryable from
            // the shopper's point of view, so they collapse to one message.
            log.warn("Downstream unreachable path={} reason={}", path, ex.getMessage());
            throw DownstreamException.from(HttpStatus.SERVICE_UNAVAILABLE, path,
                    "The service is temporarily unreachable");
        }
    }

    private String bearer(SessionTokens tokens) {
        return "Bearer " + tokens.accessToken();
    }
}