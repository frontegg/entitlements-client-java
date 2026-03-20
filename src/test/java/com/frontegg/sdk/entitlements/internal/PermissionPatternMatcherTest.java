package com.frontegg.sdk.entitlements.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPatternMatcherTest {

    @Test
    void matches_exactPattern_returnsTrue() {
        assertTrue(PermissionPatternMatcher.matches("fe.billing.read", List.of("fe.billing.read")));
    }

    @Test
    void matches_wildcardSuffix_returnsTrue() {
        assertTrue(PermissionPatternMatcher.matches("fe.billing.read", List.of("fe.billing.*")));
    }

    @Test
    void matches_wildcardAll_returnsTrue() {
        assertTrue(PermissionPatternMatcher.matches("fe.billing.read", List.of("fe.*")));
    }

    @Test
    void matches_noMatch_returnsFalse() {
        assertFalse(PermissionPatternMatcher.matches("fe.billing.read", List.of("fe.users.*")));
    }

    @Test
    void matches_partialMatch_returnsFalse() {
        // Anchor prevents partial suffix matches — "fe.billing.reads" should not match "fe.billing.read"
        assertFalse(PermissionPatternMatcher.matches("fe.billing.reads", List.of("fe.billing.read")));
    }

    @Test
    void matches_dotIsLiteralNotWildcard_returnsFalse() {
        // dot is escaped — "feXbillingXread" should NOT match "fe.billing.read"
        assertFalse(PermissionPatternMatcher.matches("feXbillingXread", List.of("fe.billing.read")));
    }

    @Test
    void matches_multiplePatterns_firstMatches_returnsTrue() {
        assertTrue(PermissionPatternMatcher.matches("fe.billing.read",
                List.of("fe.users.*", "fe.billing.*")));
    }

    @Test
    void matches_emptyPatternList_returnsFalse() {
        assertFalse(PermissionPatternMatcher.matches("fe.billing.read", List.of()));
    }

    @Test
    void matches_nullPatternList_returnsFalse() {
        assertFalse(PermissionPatternMatcher.matches("fe.billing.read", null));
    }

    @Test
    void matches_startsWithWildcard_returnsTrue() {
        assertTrue(PermissionPatternMatcher.matches("fe.billing.read", List.of("*.billing.read")));
    }

    @Test
    void matches_wildcardRequiresAtLeastOneChar() {
        // With .+ (one-or-more), "fe.billing.*" must NOT match "fe.billing." (no chars after the dot)
        assertFalse(PermissionPatternMatcher.matches("fe.billing.", List.of("fe.billing.*")));
    }

    @Test
    void matches_nullPermissionKey_returnsFalse() {
        assertFalse(PermissionPatternMatcher.matches(null, List.of("fe.billing.*")));
    }
}
