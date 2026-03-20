package com.frontegg.sdk.entitlements.internal;

import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Package-private utility that converts a user-attributes map into a {@link Struct} suitable
 * for use as a SpiceDB caveat context.
 *
 * <p>{@link String}, {@link Number}, {@link Boolean}, and nested {@link Map} attribute values
 * are supported. Any entry whose value is of an unsupported type is silently skipped to avoid
 * failing the entire check.
 *
 * <p>An optional {@link Instant} {@code at} parameter can be provided. When non-null it is
 * added to the caveat context struct as a string field named {@code "at"} using ISO-8601
 * format (e.g. {@code "2026-01-01T00:00:00Z"}). This enables time-based access checks.
 *
 * <p>This class is intentionally package-private and must not be exposed in the public API.
 */
final class CaveatContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(CaveatContextBuilder.class);

    private CaveatContextBuilder() {
        // utility class — no instances
    }

    /**
     * Builds a {@link Struct} from the provided attributes map.
     *
     * <p>Delegates to {@link #build(Map, Instant)} with a {@code null} {@code at} parameter.
     *
     * @param attributes user-supplied attributes; may be {@code null}
     * @return a populated {@link Struct}, or {@code null} if there are no attributes to encode
     */
    static Struct build(Map<String, Object> attributes) {
        return build(attributes, null);
    }

    /**
     * Builds a flat {@link Struct} from the provided attributes map and optional time parameter.
     *
     * <p>If {@code attributes} is {@code null} or empty <em>and</em> {@code at} is also
     * {@code null}, returns {@code null} so callers can skip attaching a caveat context
     * altogether.
     *
     * <p>When {@code at} is non-null it is written as an ISO-8601 string value under the
     * key {@code "at"} in the resulting struct.
     *
     * <p>Note: this method produces a flat struct. For the SpiceDB {@code targeting} caveat
     * used by feature checks (which requires a {@code user_context} wrapper), use
     * {@link #buildForTargetingCaveat(Map, Instant)} instead.
     *
     * @param attributes user-supplied attributes; may be {@code null}
     * @param at         optional point-in-time for time-based access checks; may be {@code null}
     * @return a populated {@link Struct}, or {@code null} if there is nothing to encode
     */
    static Struct build(Map<String, Object> attributes, Instant at) {
        if ((attributes == null || attributes.isEmpty()) && at == null) {
            return null;
        }

        Struct.Builder builder = Struct.newBuilder();

        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                Value protoValue = toProtoValue(entry.getValue());
                if (protoValue != null) {
                    builder.putFields(entry.getKey(), protoValue);
                }
            }
        }

        if (at != null) {
            if (attributes != null && attributes.containsKey("at")) {
                log.warn("Attribute map contains key 'at' which will be overwritten by the time-based access parameter");
            }
            builder.putFields("at", Value.newBuilder()
                    .setStringValue(at.toString())
                    .build());
        }

        // Return null if nothing was encoded (e.g. all attribute values were of unsupported types).
        // Callers rely on null meaning "no caveat context" — an empty Struct would trigger
        // PERMISSIONSHIP_CONDITIONAL_PERMISSION in SpiceDB.
        Struct result = builder.build();
        return result.getFieldsCount() == 0 ? null : result;
    }

    /**
     * Builds a {@link Struct} wrapped under a {@code user_context} key, as required by the
     * SpiceDB {@code targeting} caveat used for feature entitlement checks.
     *
     * <p>The {@code user_context} struct always contains a {@code now} field (ISO-8601).
     * When {@code at} is non-null it is used as the {@code now} value; otherwise
     * {@link Instant#now()} is used. Any additional caller-supplied attributes are also
     * placed inside {@code user_context}.
     *
     * <p>Always returns a non-null {@link Struct}.
     *
     * <p>The {@code now} field is written <em>after</em> iterating caller attributes so that
     * a caller attribute named {@code "now"} cannot overwrite the effective time value.
     *
     * @param attributes user-supplied attributes; may be {@code null}
     * @param at         optional point-in-time; may be {@code null}
     * @return a {@link Struct} with a {@code user_context} wrapper containing {@code now}
     *         and any supplied attributes
     */
    static Struct buildForTargetingCaveat(Map<String, Object> attributes, Instant at) {
        Struct.Builder userContextBuilder = Struct.newBuilder();

        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                Value protoValue = toProtoValue(entry.getValue());
                if (protoValue != null) {
                    userContextBuilder.putFields(entry.getKey(), protoValue);
                }
            }
        }

        // Write `now` AFTER iterating attributes so a caller attribute named "now"
        // cannot overwrite the effective time value (C5 fix).
        Instant effectiveAt = at != null ? at : Instant.now();
        if (attributes != null && attributes.containsKey("now")) {
            log.warn("Attribute map contains key 'now' which will be overwritten by the effective time value");
        }
        userContextBuilder.putFields("now", Value.newBuilder()
                .setStringValue(effectiveAt.toString())
                .build());

        return Struct.newBuilder()
                .putFields("user_context", Value.newBuilder()
                        .setStructValue(userContextBuilder.build())
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Value toProtoValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (obj instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        }
        if (obj instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        }
        if (obj instanceof Number n) {
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        }
        if (obj instanceof Map<?, ?> m) {
            Struct.Builder nestedBuilder = Struct.newBuilder();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                Value nestedValue = toProtoValue(entry.getValue());
                if (nestedValue != null) {
                    nestedBuilder.putFields(entry.getKey().toString(), nestedValue);
                }
            }
            return Value.newBuilder().setStructValue(nestedBuilder.build()).build();
        }
        log.warn("Unsupported caveat context value type '{}' for attribute; skipping", obj.getClass().getName());
        return null;
    }
}
