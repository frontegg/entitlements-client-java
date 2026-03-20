package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Requests an entitlement check against an HTTP route (method + path).
 *
 * <p>The authorization engine looks up matching {@code frontegg_route} relationships and
 * evaluates whether the subject is permitted to access the specified route. Route matching
 * supports regular-expression patterns stored in the authorization engine.
 *
 * <pre>{@code
 * RequestContext request = new RouteRequestContext("GET", "/api/v1/reports");
 * }</pre>
 *
 * @param method the HTTP method (e.g. {@code "GET"}, {@code "POST"}); must not be null or blank
 * @param path   the request path (e.g. {@code "/api/v1/reports"}); must not be null or blank
 * @since 0.1.0
 */
public record RouteRequestContext(String method, String path) implements RequestContext {

    /**
     * Compact canonical constructor — validates that neither {@code method} nor {@code path}
     * is null or blank.
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
        method = method.toUpperCase();
    }
}
