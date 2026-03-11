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

/**
 * Unit tests for {@link CaveatContextBuilder}.
 *
 * <p>Tests verify the conversion of user-supplied attribute maps into protobuf {@link Struct}
 * suitable for SpiceDB caveat contexts, with support for String, Boolean, Number, and null values,
 * and silent skipping of unsupported types.
 */
class CaveatContextBuilderTest {

    // -------------------------------------------------------------------------
    // Null and empty maps
    // -------------------------------------------------------------------------

    @Test
    void build_nullMap_returnsNull() {
        Struct result = CaveatContextBuilder.build(null);
        assertNull(result, "null map must return null");
    }

    @Test
    void build_emptyMap_returnsNull() {
        Struct result = CaveatContextBuilder.build(Map.of());
        assertNull(result, "empty map must return null");
    }

    // -------------------------------------------------------------------------
    // Supported types
    // -------------------------------------------------------------------------

    @Test
    void build_stringValue_convertsCorrectly() {
        Struct result = CaveatContextBuilder.build(Map.of("key", "value"));
        assertNotNull(result);
        assertEquals(1, result.getFieldsCount());
        Value value = result.getFieldsOrThrow("key");
        assertEquals("value", value.getStringValue());
    }

    @Test
    void build_booleanValue_convertsCorrectly() {
        Struct result = CaveatContextBuilder.build(Map.of("enabled", true));
        assertNotNull(result);
        assertEquals(1, result.getFieldsCount());
        Value value = result.getFieldsOrThrow("enabled");
        assertEquals(true, value.getBoolValue());
    }

    @Test
    void build_integerValue_convertsToDouble() {
        Struct result = CaveatContextBuilder.build(Map.of("count", 42));
        assertNotNull(result);
        Value value = result.getFieldsOrThrow("count");
        assertEquals(42.0, value.getNumberValue());
    }

    @Test
    void build_doubleValue_convertsCorrectly() {
        Struct result = CaveatContextBuilder.build(Map.of("price", 19.99));
        assertNotNull(result);
        Value value = result.getFieldsOrThrow("price");
        assertEquals(19.99, value.getNumberValue());
    }

    @Test
    void build_longValue_convertsToDouble() {
        Struct result = CaveatContextBuilder.build(Map.of("timestamp", 1609459200L));
        assertNotNull(result);
        Value value = result.getFieldsOrThrow("timestamp");
        assertEquals(1609459200.0, value.getNumberValue());
    }

    @Test
    void build_nullValueInMap_convertsToNullValue() {
        // Map.of() does not allow null values, so use HashMap instead
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("optional", null);

        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        assertEquals(1, result.getFieldsCount());
        Value value = result.getFieldsOrThrow("optional");
        assertEquals(NullValue.NULL_VALUE, value.getNullValue());
    }

    // -------------------------------------------------------------------------
    // Unsupported types
    // -------------------------------------------------------------------------

    @Test
    void build_listValue_skipped() {
        Struct result = CaveatContextBuilder.build(Map.of("list", List.of("a", "b")));
        assertNotNull(result);
        // unsupported types are skipped, so the struct should be empty
        assertEquals(0, result.getFieldsCount(),
                "list values must be skipped; struct should be empty");
    }

    @Test
    void build_mapValue_skipped() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("nested", Map.of("key", "value"));
        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        assertEquals(0, result.getFieldsCount(),
                "nested map values must be skipped; struct should be empty");
    }

    @Test
    void build_customObjectValue_skipped() {
        Struct result = CaveatContextBuilder.build(Map.of("object", new Object()));
        assertNotNull(result);
        assertEquals(0, result.getFieldsCount(),
                "custom object values must be skipped; struct should be empty");
    }

    // -------------------------------------------------------------------------
    // Mixed type handling
    // -------------------------------------------------------------------------

    @Test
    void build_mixedSupportedTypes_allIncluded() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", "Alice");
        attrs.put("active", true);
        attrs.put("age", 30);
        attrs.put("score", 95.5);

        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        assertEquals(4, result.getFieldsCount(), "all supported types must be included");

        assertEquals("Alice", result.getFieldsOrThrow("name").getStringValue());
        assertEquals(true, result.getFieldsOrThrow("active").getBoolValue());
        assertEquals(30.0, result.getFieldsOrThrow("age").getNumberValue());
        assertEquals(95.5, result.getFieldsOrThrow("score").getNumberValue());
    }

    @Test
    void build_mixedWithUnsupported_onlySupportedIncluded() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", "Bob");
        attrs.put("roles", List.of("admin", "user")); // unsupported
        attrs.put("verified", true);
        attrs.put("config", Map.of("key", "val")); // unsupported

        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result);
        assertEquals(2, result.getFieldsCount(),
                "unsupported types must be skipped; only supported types included");

        assertEquals("Bob", result.getFieldsOrThrow("name").getStringValue());
        assertEquals(true, result.getFieldsOrThrow("verified").getBoolValue());
    }

    @Test
    void build_mapWithOnlyUnsupportedTypes_returnsEmptyStruct() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("list", List.of(1, 2, 3));
        attrs.put("nested", Map.of("a", "b"));
        attrs.put("object", new Object());

        Struct result = CaveatContextBuilder.build(attrs);
        assertNotNull(result, "result must not be null (build returns empty Struct, not null)");
        assertEquals(0, result.getFieldsCount(), "struct must be empty when all types unsupported");
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void build_emptyString_included() {
        Struct result = CaveatContextBuilder.build(Map.of("empty", ""));
        assertNotNull(result);
        assertEquals("", result.getFieldsOrThrow("empty").getStringValue());
    }

    @Test
    void build_zeroValue_included() {
        Struct result = CaveatContextBuilder.build(Map.of("zero", 0));
        assertNotNull(result);
        assertEquals(0.0, result.getFieldsOrThrow("zero").getNumberValue());
    }

    @Test
    void build_falseValue_included() {
        Struct result = CaveatContextBuilder.build(Map.of("flag", false));
        assertNotNull(result);
        assertEquals(false, result.getFieldsOrThrow("flag").getBoolValue());
    }

    // -------------------------------------------------------------------------
    // build(attributes, at) — time-based access parameter
    // -------------------------------------------------------------------------

    @Test
    void build_withAtOnly_returnsStructWithAtField() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        Struct result = CaveatContextBuilder.build(null, at);

        assertNotNull(result, "result must not be null when at is non-null");
        assertEquals(1, result.getFieldsCount(), "struct must contain exactly the 'at' field");
        Value atValue = result.getFieldsOrThrow("at");
        assertEquals("2026-01-01T00:00:00Z", atValue.getStringValue(),
                "'at' field must be stored as ISO-8601 string");
    }

    @Test
    void build_withAttributesAndAt_includesBothInStruct() {
        Instant at = Instant.parse("2025-06-15T12:30:00Z");
        Map<String, Object> attrs = Map.of("plan", "enterprise");

        Struct result = CaveatContextBuilder.build(attrs, at);

        assertNotNull(result);
        assertEquals(2, result.getFieldsCount(), "struct must contain attribute field and 'at' field");
        assertEquals("enterprise", result.getFieldsOrThrow("plan").getStringValue());
        assertEquals("2025-06-15T12:30:00Z", result.getFieldsOrThrow("at").getStringValue());
    }

    @Test
    void build_withNullAttributesAndNullAt_returnsNull() {
        Struct result = CaveatContextBuilder.build(null, null);
        assertNull(result, "null attributes and null at must return null");
    }

    @Test
    void build_withEmptyAttributesAndNullAt_returnsNull() {
        Struct result = CaveatContextBuilder.build(Map.of(), null);
        assertNull(result, "empty attributes and null at must return null");
    }

    @Test
    void build_withEmptyAttributesAndAt_returnsStructWithAtField() {
        Instant at = Instant.parse("2026-03-11T08:00:00Z");

        Struct result = CaveatContextBuilder.build(Map.of(), at);

        assertNotNull(result, "result must not be null when at is non-null");
        assertEquals(1, result.getFieldsCount());
        assertEquals("2026-03-11T08:00:00Z", result.getFieldsOrThrow("at").getStringValue());
    }

    @Test
    void build_atIsIso8601Format_nanoSecondPrecision() {
        Instant at = Instant.parse("2026-01-01T00:00:00.123456789Z");

        Struct result = CaveatContextBuilder.build(null, at);

        assertNotNull(result);
        // Instant.toString() preserves sub-second precision
        assertEquals(at.toString(), result.getFieldsOrThrow("at").getStringValue(),
                "'at' field must use Instant.toString() ISO-8601 format");
    }

    @Test
    void build_singleArgMethod_delegatesToTwoArgWithNullAt() {
        // Verify the single-arg overload still works correctly (delegates with null at)
        Struct result = CaveatContextBuilder.build(Map.of("key", "value"));
        assertNotNull(result);
        assertEquals(1, result.getFieldsCount(), "single-arg build must not add 'at' field");
        assertEquals("value", result.getFieldsOrThrow("key").getStringValue());
        assertFalse(result.containsFields("at"), "single-arg build must not include 'at' field");
    }
}
