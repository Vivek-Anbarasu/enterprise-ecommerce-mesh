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

    private static final long serialVersionUID = 1L;

    /**
     * Shadows {@link DownstreamException}'s field of the same name and overrides its
     * getter to read this one. Both are assigned the same value, so the accessor
     * behaves identically either way - but a redundant copy of a field on a
     * Serializable type is one more thing that can drift out of step. Safe to drop
     * both and inherit the generated {@code getPath()}.
     */
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