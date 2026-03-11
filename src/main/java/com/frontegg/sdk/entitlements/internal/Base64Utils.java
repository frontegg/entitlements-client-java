package com.frontegg.sdk.entitlements.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Package-private utility for URL-safe Base64 encoding of SpiceDB object IDs.
 *
 * <p>All object IDs sent to SpiceDB must be encoded with this method to match the behaviour of
 * the TypeScript SDK's {@code normalizeObjectId} function. The encoding uses the URL-safe
 * Base64 alphabet (RFC 4648 §5) with no padding characters.
 *
 * <p>This class is intentionally package-private and must not be exposed in the public API.
 */
final class Base64Utils {

    private Base64Utils() {
        // utility class — no instances
    }

    /**
     * Encodes {@code value} to a URL-safe, no-padding Base64 string using UTF-8 byte encoding.
     *
     * <p>Produces identical output to the TypeScript SDK's {@code normalizeObjectId}:
     * <pre>
     *   Buffer.from(value, 'utf8').toString('base64url')
     * </pre>
     *
     * @param value the string to encode; may be {@code null}
     * @return the encoded string, or {@code null} if {@code value} is {@code null}
     */
    static String encode(String value) {
        if (value == null) {
            return null;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a URL-safe Base64 string (with or without padding) back to the original UTF-8
     * string. This is the inverse of {@link #encode(String)}.
     *
     * @param encoded the URL-safe Base64 encoded string; may be {@code null} or empty
     * @return the decoded string, or the original value if it is {@code null} or empty
     */
    static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return encoded;
        }
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
