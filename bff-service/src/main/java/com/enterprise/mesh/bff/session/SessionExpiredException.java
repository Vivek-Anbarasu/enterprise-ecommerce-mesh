package com.enterprise.mesh.bff.session;

import lombok.Getter;

/**
 * Raised when a request presents a session cookie that no longer resolves to a
 * usable token bundle.
 *
 * <p>Three different situations produce this and they are not equally interesting:
 * no session at all, a session whose refresh token has been revoked, and a session
 * whose access token expired while the refresh grant itself was rejected. All three
 * end the same way from the shopper's point of view - they are sent back through
 * the login flow - so they are deliberately collapsed into one signal rather than
 * surfaced as three distinguishable errors that would only tempt a client to treat
 * a recoverable case as fatal.
 *
 * <p>{@code anonymous} is true when the request had no session to begin with, as
 * opposed to having a broken one.
 */
@Getter
public class SessionExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean anonymous;

    public SessionExpiredException(String message) {
        this(message, false, null);
    }

    public SessionExpiredException(String message, boolean anonymous, Throwable cause) {
        super(message, cause);
        this.anonymous = anonymous;
    }
}