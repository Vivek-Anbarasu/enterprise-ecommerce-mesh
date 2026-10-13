package com.enterprise.mesh.bff.client;

/**
 * The gateway rejected the access token this BFF supplied.
 *
 * <p>Worth its own type despite being a 401, because it is a different event from
 * "the shopper has no session". The session exists - it is the token inside it that
 * was refused - so the recovery is to re-run the refresh and retry, not to send the
 * browser back through the login flow. With a 90 second refresh skew this should not
 * happen at all; it means the BFF's clock is far enough out of step with the token
 * issuer's, or the signing key rotated underneath it, and that is worth surfacing
 * loudly rather than absorbing as an ordinary 401.
 */
public class DownstreamUnauthorizedException extends DownstreamException {

    private final String path;

    public DownstreamUnauthorizedException(String path, Throwable cause) {
        super(401, path, "The api-gateway rejected the access token held for this session", cause);
        this.path = path;
    }

    @Override
    public String getPath() {
        return path;
    }
}