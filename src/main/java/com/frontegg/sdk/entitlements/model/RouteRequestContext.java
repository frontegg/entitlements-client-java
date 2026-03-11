package com.frontegg.sdk.entitlements.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Requests an entitlement check against an HTTP route (method + path).
 *
 * <p>The authorization engine looks up matching {@code frontegg_route} relationships and
 * evaluates whether the subject is permitted to access the specified route. Route matching
 * supports regular-expression patterns stored in the authorization engine.
 *
 * <p>The optional {@code at} parameter allows checking entitlement at a specific point in time
 * (past or future). When {@code at} is non-null it is forwarded to the authorization engine as
 * an {@code "at"} field in the caveat context using ISO-8601 string format. A {@code null}
 * value means "check at the current time".
 *
 * <pre>{@code
 * // Check at current time
 * RequestContext request = new RouteRequestContext("GET", "/api/v1/reports");
 *
 * // Check at a specific point in time
 * RequestContext request = new RouteRequestContext("GET", "/api/v1/reports",
 *         Instant.parse("2026-01-01T00:00:00Z"));
 * }</pre>
 *
 * @param method the HTTP method (e.g. {@code "GET"}, {@code "POST"}); must not be null or blank
 * @param path   the request path (e.g. {@code "/api/v1/reports"}); must not be null or blank
 * @param at     the point in time at which to evaluate entitlement; {@code null} means
 *               current time
 * @since 0.1.0
 */
public record RouteRequestContext(String method, String path, Instant at) implements RequestContext {

    /**
     * Compact canonical constructor — validates that neither {@code method} nor {@code path}
     * is null or blank. {@code at} is nullable; {@code null} means "check at the current time".
     *
     * @throws NullPointerException     if {@code method} or {@code path} is {@code null}
     * @throws IllegalArgumentException if {@code method} or {@code path} is blank
     */
    public RouteRequestContext {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        if (method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        // at is nullable — null means "check at current time"
    }

    /**
     * Convenience constructor for checking at the current time.
     *
     * @param method the HTTP method; must not be null or blank
     * @param path   the request path; must not be null or blank
     */
    public RouteRequestContext(String method, String path) {
        this(method, path, null);
    }
}
