package com.frontegg.sdk.entitlements.internal;

import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;

/**
 * Package-private composite key for the entitlements result cache.
 *
 * <p>Equality and hashing are derived structurally from the {@code subject} and
 * {@code request} components via the standard {@code record} semantics, so any two keys with
 * equal subject and request instances will be considered the same cache entry.
 *
 * @param subject the subject being checked; must implement meaningful {@code equals}/{@code hashCode}
 * @param request the resource/permission being checked; must implement meaningful {@code equals}/{@code hashCode}
 */
record EntitlementsCacheKey(SubjectContext subject, RequestContext request) {}
