package com.enterprise.mesh.bff.client;

import lombok.Getter;

import org.springframework.http.HttpStatusCode;

/**
 * A call to the api-gateway (and through it, to a business service) that did not
 * succeed.
 *
 * <p>Carries only the status and the path. The downstream response body is
 * deliberately not attached: it is rendered to a browser-facing client, and
 * gateway payloads can quote upstream URLs and service names. The detail string is
 * written by the BFF and is safe to show.
 */
@Getter
public class DownstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String path;

    public DownstreamException(int status, String path, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.path = path;
    }

    public static DownstreamException from(HttpStatusCode status, String path, String detail) {
        return new DownstreamException(status.value(), path, detail, null);
    }
}