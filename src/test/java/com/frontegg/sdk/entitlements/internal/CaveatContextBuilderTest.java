package com.frontegg.sdk.entitlements.internal;

import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CaveatContextBuilder}.
 */
class CaveatContextBuilderTest {

    // =========================================================================
    // build(attributes) / build(attributes, at) — flat struct (FGA / active_at caveat)
    // =========================================================================

    @Test
    void build_nullMap_returnsNull() {
        assertNull(CaveatContextBuilder.build(null), "null map must return null");
    }

    @Test
    void build_emptyMap_returnsNull() {
        assertNull(CaveatContextBuilder.build(Map.of()), "empty map must return null");
    }

    @Test
    void build_stringValue_convertsCorrectly() {
        Struct result = CaveatContextBuilder.build(Map.of("key", "value"));
        assertNotNull(result);
        assertEquals("value", result.getFieldsOrThrow("key").getStringValue());
    }

    @Test
    void build_booleanValue_convertsCorrectly() {
        Struct result = CaveatContextBuilder.build(Map.of("enabled", true));
        assertNotNull(result);
        assertEquals(true, result.getFieldsOrThrow("enabled").getBoolValue());
    }

    @Test
    void build_integerValue_convertsToDouble() {
        Struct result = CaveatContextBuilder.build(Map.of("count", 42));
        assertNotNull(result);
        assertEquals(42.0, result.getFieldsOrThrow("count").getNumberValue());
    }

    @Test
    void build_doubleValue_convertsCorrectly() {
        Struct result = CaveatContextBuilder.build(Map.of("price", 19.99));
        assertNotNull(result);
        assertEquals(19.99, result.getFieldsOrThrow("price").getNumberValue());
    }

    @Test
    void build_longValue_convertsToDouble() {
        Struct result = CaveatContextBuilder.build(Map.of("timestamp", 1609459200L));
        assertNotNull(result);
        assertEquals(1609459200.0, result.getFieldsOrThrow("timestamp").getNumberValue());
    }

    @Test
    void build_nullValueInMap_convertsToNullValue() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("optional", null);
        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        assertEquals(NullValue.NULL_VALUE, result.getFieldsOrThrow("optional").getNullValue());
    }

    @Test
    void build_nestedMapValue_convertsToNestedStruct() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("nested", Map.of("key", "value"));
        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        Struct nested = result.getFieldsOrThrow("nested").getStructValue();
        assertEquals("value", nested.getFieldsOrThrow("key").getStringValue());
    }

    @Test
    void build_listValue_skipped() {
        // All attributes are of unsupported type → nothing to encode → null (no caveat context)
        Struct result = CaveatContextBuilder.build(Map.of("list", List.of("a", "b")));
        assertNull(result, "all-skipped attributes must produce null, not an empty Struct");
    }

    @Test
    void build_customObjectValue_skipped() {
        // All attributes are of unsupported type → nothing to encode → null (no caveat context)
        Struct result = CaveatContextBuilder.build(Map.of("object", new Object()));
        assertNull(result, "all-skipped attributes must produce null, not an empty Struct");
    }

    @Test
    void build_mixedSupportedTypes_allIncluded() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", "Alice");
        attrs.put("active", true);
        attrs.put("age", 30);
        attrs.put("score", 95.5);
        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        assertEquals(4, result.getFieldsCount());
        assertEquals("Alice", result.getFieldsOrThrow("name").getStringValue());
        assertEquals(true, result.getFieldsOrThrow("active").getBoolValue());
        assertEquals(30.0, result.getFieldsOrThrow("age").getNumberValue());
        assertEquals(95.5, result.getFieldsOrThrow("score").getNumberValue());
    }

    @Test
    void build_withAtOnly_returnsStructWithAtField() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        Struct result = CaveatContextBuilder.build(null, at);
        assertNotNull(result);
        assertEquals(1, result.getFieldsCount());
        assertEquals("2026-01-01T00:00:00Z", result.getFieldsOrThrow("at").getStringValue());
    }

    @Test
    void build_withAttributesAndAt_includesBothInStruct() {
        Instant at = Instant.parse("2025-06-15T12:30:00Z");
        Struct result = CaveatContextBuilder.build(Map.of("plan", "enterprise"), at);
        assertNotNull(result);
        assertEquals(2, result.getFieldsCount());
        assertEquals("enterprise", result.getFieldsOrThrow("plan").getStringValue());
        assertEquals("2025-06-15T12:30:00Z", result.getFieldsOrThrow("at").getStringValue());
    }

    @Test
    void build_withNullAttributesAndNullAt_returnsNull() {
        assertNull(CaveatContextBuilder.build(null, null));
    }

    @Test
    void build_withEmptyAttributesAndNullAt_returnsNull() {
        assertNull(CaveatContextBuilder.build(Map.of(), null));
    }

    @Test
    void build_singleArgMethod_doesNotAddAtField() {
        Struct result = CaveatContextBuilder.build(Map.of("key", "value"));
        assertNotNull(result);
        assertEquals(1, result.getFieldsCount());
        assertEquals("value", result.getFieldsOrThrow("key").getStringValue());
        assertFalse(result.containsFields("at"), "single-arg build must not include 'at'");
    }

    // =========================================================================
    // buildForTargetingCaveat — user_context wrapper (feature / targeting caveat)
    // =========================================================================

    /** Returns the inner {@code user_context} struct from the top-level result. */
    private static Struct userContext(Struct result) {
        assertNotNull(result);
        assertTrue(result.containsFields("user_context"), "top-level struct must contain 'user_context'");
        return result.getFieldsOrThrow("user_context").getStructValue();
    }

    @Test
    void buildForTargetingCaveat_nullAttributes_returnsStructWithNowOnly() {
        Struct result = CaveatContextBuilder.buildForTargetingCaveat(null, null);
        Struct uc = userContext(result);
        assertEquals(1, uc.getFieldsCount());
        assertTrue(uc.containsFields("now"));
    }

    @Test
    void buildForTargetingCaveat_withAt_usesItAsNow() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        Struct uc = userContext(CaveatContextBuilder.buildForTargetingCaveat(null, at));
        assertEquals("2026-01-01T00:00:00Z", uc.getFieldsOrThrow("now").getStringValue());
    }

    @Test
    void buildForTargetingCaveat_withNullAt_usesCurrentTimeAsNow() {
        Instant before = Instant.now();
        Struct uc = userContext(CaveatContextBuilder.buildForTargetingCaveat(null, null));
        Instant after = Instant.now();
        Instant now = Instant.parse(uc.getFieldsOrThrow("now").getStringValue());
        assertTrue(!now.isBefore(before) && !now.isAfter(after));
    }

    @Test
    void buildForTargetingCaveat_withAttributes_includesThemInUserContext() {
        Struct uc = userContext(CaveatContextBuilder.buildForTargetingCaveat(
                Map.of("plan", "enterprise"), Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals("enterprise", uc.getFieldsOrThrow("plan").getStringValue());
        assertEquals("2026-01-01T00:00:00Z", uc.getFieldsOrThrow("now").getStringValue());
    }

    @Test
    void buildForTargetingCaveat_nestedMapAttribute_serializedCorrectly() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("meta", Map.of("tier", "pro"));
        Struct uc = userContext(CaveatContextBuilder.buildForTargetingCaveat(attrs, null));
        Struct meta = uc.getFieldsOrThrow("meta").getStructValue();
        assertEquals("pro", meta.getFieldsOrThrow("tier").getStringValue());
    }

    @Test
    void buildForTargetingCaveat_attributeNamedNow_doesNotOverwriteEffectiveAt() {
        // C5: an attribute named "now" must NOT overwrite the effective time value
        Instant at = Instant.parse("2026-06-01T12:00:00Z");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("now", "attacker-value");

        Struct uc = userContext(CaveatContextBuilder.buildForTargetingCaveat(attrs, at));

        assertEquals(at.toString(), uc.getFieldsOrThrow("now").getStringValue(),
                "user_context.now must equal the at parameter value, not the attacker-supplied attribute");
    }

    @Test
    void buildForTargetingCaveat_nowAttributeAsNumber_systemNowOverwrites() {
        // C5 variant: if "now" is provided as a numeric value (Long), the system time must still overwrite it
        Instant at = Instant.parse("2026-01-15T10:30:00Z");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("now", 1000000L);  // User tries to inject a long value

        Struct uc = userContext(CaveatContextBuilder.buildForTargetingCaveat(attrs, at));

        assertEquals(at.toString(), uc.getFieldsOrThrow("now").getStringValue(),
                "user_context.now must be the ISO-8601 timestamp from the at parameter, not the user-supplied numeric value");
    }
}
