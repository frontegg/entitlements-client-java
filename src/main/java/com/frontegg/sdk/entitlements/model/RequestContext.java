package com.frontegg.sdk.entitlements.model;

/**
 * Defines <em>what</em> entitlement is being checked for a given subject.
 *
 * <p>{@code RequestContext} is a sealed interface; all permitted implementations are defined in
 * this package. Callers can exhaustively switch over all cases using a Java 17+ pattern-matching
 * {@code switch} expression without a default branch:
 *
 * <pre>{@code
 * switch (requestContext) {
 *     case FeatureRequestContext    f -> handleFeature(f);
 *     case PermissionRequestContext p -> handlePermission(p);
 *     case RouteRequestContext      r -> handleRoute(r);
 *     case EntityRequestContext     e -> handleEntity(e);
 * }
 * }</pre>
 *
 * <p>Permitted implementations:
 * <ul>
 *   <li>{@link FeatureRequestContext} — check access to a named feature flag</li>
 *   <li>{@link PermissionRequestContext} — check one or more named permissions</li>
 *   <li>{@link RouteRequestContext} — check whether an HTTP route is accessible</li>
 *   <li>{@link EntityRequestContext} — FGA check between two entities via a named relation</li>
 * </ul>
 *
 * @since 0.1.0
 */
public sealed interface RequestContext
        permits FeatureRequestContext,
                PermissionRequestContext,
                RouteRequestContext,
                EntityRequestContext {
}
