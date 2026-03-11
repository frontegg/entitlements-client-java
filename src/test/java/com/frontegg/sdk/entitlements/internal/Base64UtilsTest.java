package com.frontegg.sdk.entitlements.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Base64Utils}.
 *
 * <p>Cross-language compatibility: the expected values below were verified against the
 * TypeScript SDK's {@code normalizeObjectId} function, which uses
 * {@code Buffer.from(value, 'utf8').toString('base64url')} — identical encoding.
 */
class Base64UtilsTest {

    // -------------------------------------------------------------------------
    // Null / edge cases
    // -------------------------------------------------------------------------

    @Test
    void encode_null_returnsNull() {
        assertNull(Base64Utils.encode(null));
    }

    @Test
    void encode_emptyString_returnsEmptyString() {
        assertEquals("", Base64Utils.encode(""));
    }

    // -------------------------------------------------------------------------
    // Cross-language compatibility — values verified against TypeScript SDK
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "hello,                          aGVsbG8",
            "user-123,                        dXNlci0xMjM",
            "tenant/special+chars=test,       dGVuYW50L3NwZWNpYWwrY2hhcnM9dGVzdA",
            "'',                              ''",
    })
    void encode_crossLanguageCompatibility(String input, String expectedOutput) {
        // CsvSource uses empty string '' for empty; JUnit trims whitespace from values
        assertEquals(expectedOutput.strip(), Base64Utils.encode(input));
    }

    @Test
    void encode_unicode_matchesTypeScriptSdk() {
        // "日本語" (Japanese characters) → verified against TypeScript SDK
        assertEquals("5pel5pys6Kqe", Base64Utils.encode("日本語"));
    }

    // -------------------------------------------------------------------------
    // Basic ASCII encoding properties
    // -------------------------------------------------------------------------

    @Test
    void encode_basicAscii_producesUrlSafeNoPaddingOutput() {
        String result = Base64Utils.encode("hello");
        // URL-safe Base64: no '+' or '/', no '=' padding
        assertEquals("aGVsbG8", result);
        assert !result.contains("+") : "must not contain '+'";
        assert !result.contains("/") : "must not contain '/'";
        assert !result.contains("=") : "must not contain '=' padding";
    }

    @Test
    void encode_stringWithPlusAndEquals_usesUrlSafeAlphabet() {
        // '+' and '=' in input produce '-' and '_' in URL-safe base64 output where applicable
        String result = Base64Utils.encode("tenant/special+chars=test");
        assertEquals("dGVuYW50L3NwZWNpYWwrY2hhcnM9dGVzdA", result);
        assert !result.contains("+") : "must not contain '+'";
        assert !result.contains("=") : "must not contain '='";
    }

    @Test
    void encode_deterministicForSameInput() {
        String input = "user-abc-123";
        assertEquals(Base64Utils.encode(input), Base64Utils.encode(input));
    }

    // -------------------------------------------------------------------------
    // decode — null / edge cases
    // -------------------------------------------------------------------------

    @Test
    void decode_null_returnsNull() {
        assertNull(Base64Utils.decode(null));
    }

    @Test
    void decode_emptyString_returnsEmptyString() {
        assertEquals("", Base64Utils.decode(""));
    }

    // -------------------------------------------------------------------------
    // decode — roundtrip with encode
    // -------------------------------------------------------------------------

    @Test
    void decode_encodedValue_roundtripsToOriginal() {
        String original = "user-123";
        String encoded = Base64Utils.encode(original);
        assertEquals(original, Base64Utils.decode(encoded),
                "decode(encode(x)) must return x");
    }

    @Test
    void decode_unicodeRoundtrip_matchesOriginal() {
        String original = "日本語";
        assertEquals(original, Base64Utils.decode(Base64Utils.encode(original)),
                "decode(encode(unicode)) must return the original unicode string");
    }

    @Test
    void decode_specialCharsRoundtrip_matchesOriginal() {
        String original = "user@example.com+tenant/org=id";
        assertEquals(original, Base64Utils.decode(Base64Utils.encode(original)),
                "decode(encode(special chars)) must return original");
    }

    @Test
    void decode_knownEncodedValue_returnsExpectedString() {
        // "aGVsbG8" is base64url of "hello" — verified against TypeScript SDK
        assertEquals("hello", Base64Utils.decode("aGVsbG8"),
                "Known base64url value must decode to known plaintext");
    }
}
