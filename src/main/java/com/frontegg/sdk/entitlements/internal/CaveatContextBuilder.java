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
 * <p>Only {@link String}, {@link Number}, and {@link Boolean} attribute values are supported.
 * Any entry whose value is of an unsupported type is silently skipped to avoid failing the
 * entire check.
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
     * Builds a {@link Struct} from the provided attributes map and optional time parameter.
     *
     * <p>If {@code attributes} is {@code null} or empty <em>and</em> {@code at} is also
     * {@code null}, returns {@code null} so callers can skip attaching a caveat context
     * altogether (SpiceDB treats a missing context differently from an empty context in some
     * versions).
     *
     * <p>When {@code at} is non-null it is written as an ISO-8601 string value under the
     * key {@code "at"} in the resulting struct.
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
                    .setStringValue(at.toString())  // ISO-8601 format
                    .build());
        }

        return builder.build();
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
        log.warn("Unsupported caveat context value type '{}' for attribute; skipping", obj.getClass().getName());
        return null;
    }
}
