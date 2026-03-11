package com.frontegg.sdk.entitlements.config;

import com.frontegg.sdk.entitlements.exception.ConfigurationMissingException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigurationTest {

    // -------------------------------------------------------------------------
    // Happy path — builder with all required fields
    // -------------------------------------------------------------------------

    @Test
    void build_withAllRequiredFields_succeeds() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("grpc.authz.example.com:443")
                .engineToken("my-secret-token")
                .build();

        assertNotNull(config);
        assertEquals("grpc.authz.example.com:443", config.getEngineEndpoint());
    }

    // -------------------------------------------------------------------------
    // Missing required fields
    // -------------------------------------------------------------------------

    @Test
    void build_missingEngineEndpoint_throwsConfigurationMissingException() {
        ConfigurationMissingException ex = assertThrows(ConfigurationMissingException.class,
                () -> ClientConfiguration.builder()
                        .engineToken("my-token")
                        .build());

        assertEquals("engineEndpoint", ex.getFieldName());
        assertTrue(ex.getMessage().contains("engineEndpoint"));
    }

    @Test
    void build_blankEngineEndpoint_throwsConfigurationMissingException() {
        ConfigurationMissingException ex = assertThrows(ConfigurationMissingException.class,
                () -> ClientConfiguration.builder()
                        .engineEndpoint("   ")
                        .engineToken("my-token")
                        .build());

        assertEquals("engineEndpoint", ex.getFieldName());
    }

    @Test
    void build_missingEngineToken_throwsConfigurationMissingException() {
        ConfigurationMissingException ex = assertThrows(ConfigurationMissingException.class,
                () -> ClientConfiguration.builder()
                        .engineEndpoint("localhost:50051")
                        .build());

        assertEquals("engineToken", ex.getFieldName());
        assertTrue(ex.getMessage().contains("engineToken"));
    }

    // -------------------------------------------------------------------------
    // toString() must not leak the token
    // -------------------------------------------------------------------------

    @Test
    void toString_containsRedactedToken_andNotActualToken() {
        String secretToken = "super-secret-bearer-token-xyz";
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(secretToken)
                .build();

        String repr = config.toString();

        assertTrue(repr.contains("[REDACTED]"),
                "toString() must contain [REDACTED]");
        assertFalse(repr.contains(secretToken),
                "toString() must NOT contain the actual token value");
    }

    // -------------------------------------------------------------------------
    // engineToken(String) and engineToken(Supplier) both work
    // -------------------------------------------------------------------------

    @Test
    void engineToken_staticString_supplierReturnsIt() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("static-token")
                .build();

        assertEquals("static-token", config.getEngineToken().get());
    }

    @Test
    void engineToken_supplier_supplierIsInvoked() {
        AtomicInteger callCount = new AtomicInteger(0);
        Supplier<String> dynamicToken = () -> {
            callCount.incrementAndGet();
            return "dynamic-token-" + callCount.get();
        };

        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(dynamicToken)
                .build();

        assertEquals("dynamic-token-1", config.getEngineToken().get());
        assertEquals("dynamic-token-2", config.getEngineToken().get());
        assertEquals(2, callCount.get());
    }

    // -------------------------------------------------------------------------
    // Default values
    // -------------------------------------------------------------------------

    @Test
    void build_defaultValues_areApplied() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("my-token")
                .build();

        assertEquals(Duration.ofSeconds(5), config.getRequestTimeout(),
                "default requestTimeout should be 5s");
        assertEquals(Duration.ofSeconds(15), config.getBulkRequestTimeout(),
                "default bulkRequestTimeout should be 15s");
        assertEquals(3, config.getMaxRetries(),
                "default maxRetries should be 3");
        assertTrue(config.isUseTls(),
                "default useTls should be true");
    }

    @Test
    void build_customValues_overrideDefaults() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("my-token")
                .requestTimeout(Duration.ofSeconds(2))
                .bulkRequestTimeout(Duration.ofSeconds(10))
                .maxRetries(0)
                .useTls(false)
                .build();

        assertEquals(Duration.ofSeconds(2), config.getRequestTimeout());
        assertEquals(Duration.ofSeconds(10), config.getBulkRequestTimeout());
        assertEquals(0, config.getMaxRetries());
        assertFalse(config.isUseTls());
    }

    @Test
    void maxRetries_negativeValue_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientConfiguration.builder()
                        .engineEndpoint("localhost:50051")
                        .engineToken("my-token")
                        .maxRetries(-1)
                        .build());
    }
}
