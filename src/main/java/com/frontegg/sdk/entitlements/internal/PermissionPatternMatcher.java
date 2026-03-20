package com.frontegg.sdk.entitlements.internal;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Package-private utility for wildcard permission pattern matching.
 *
 * <p>Matches a permission key against a list of patterns using the same rules as the
 * TypeScript SDK:
 * <ul>
 *   <li>{@code .} is treated as a literal dot (not a regex wildcard)</li>
 *   <li>{@code *} matches one or more characters (mapped to {@code .+})</li>
 *   <li>The match is anchored — the pattern must match the full permission key</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>
 *   "fe.billing.read"  matches pattern "fe.billing.read"   → true  (exact)
 *   "fe.billing.read"  matches pattern "fe.billing.*"      → true  (wildcard)
 *   "fe.billing.read"  matches pattern "fe.*"              → true  (wildcard)
 *   "fe.billing.read"  matches pattern "fe.users.*"        → false (no match)
 *   "fe.billing.read"  matches pattern "fe.billing.reads"  → false (not full match)
 * </pre>
 */
final class PermissionPatternMatcher {

    private PermissionPatternMatcher() {
        // utility class — no instances
    }

    /**
     * Cache of compiled {@link Pattern} objects keyed by raw permission pattern string.
     * Avoids recompiling the same regex on every call — patterns are immutable once stored.
     */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if {@code permissionKey} matches at least one pattern in
     * {@code patterns}.
     *
     * @param permissionKey the permission key to test; may be {@code null}
     * @param patterns      the list of patterns to test against; may be {@code null} or empty
     * @return {@code true} if a match is found, {@code false} otherwise
     */
    static boolean matches(String permissionKey, List<String> patterns) {
        if (permissionKey == null) return false;
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            Pattern compiled = PATTERN_CACHE.computeIfAbsent(pattern, p ->
                    Pattern.compile("^" + p.replace(".", "\\.").replace("*", ".+") + "$"));
            if (compiled.matcher(permissionKey).matches()) {
                return true;
            }
        }
        return false;
    }
}
