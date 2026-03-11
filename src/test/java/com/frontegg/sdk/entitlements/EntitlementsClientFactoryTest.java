package com.frontegg.sdk.entitlements;

import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.exception.ConfigurationMissingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntitlementsClientFactoryTest {

    // -------------------------------------------------------------------------
    // Null / invalid config guard
    // -------------------------------------------------------------------------

    @Test
    void create_nullConfig_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> EntitlementsClientFactory.create(null));
    }

    // -------------------------------------------------------------------------
    // Configuration validation — missing required fields
    // -------------------------------------------------------------------------

    @Test
    void create_missingEngineEndpoint_throwsConfigurationMissingException() {
        ConfigurationMissingException ex = assertThrows(ConfigurationMissingException.class,
                () -> ClientConfiguration.builder()
                        .engineToken("test-token")
                        .build());

        assertEquals("engineEndpoint", ex.getFieldName());
    }

    @Test
    void create_missingEngineToken_throwsConfigurationMissingException() {
        ConfigurationMissingException ex = assertThrows(ConfigurationMissingException.class,
                () -> ClientConfiguration.builder()
                        .engineEndpoint("localhost:50051")
                        .build());

        assertEquals("engineToken", ex.getFieldName());
    }

    // -------------------------------------------------------------------------
    // Valid config — channel is lazy so no real connection is made
    // -------------------------------------------------------------------------

    @Test
    void create_validConfig_returnsNonNullClient() {
        ClientConfiguration config = validConfig().build();

        EntitlementsClient client = EntitlementsClientFactory.create(config);

        assertNotNull(client);
        client.close();
    }

    @Test
    void create_validConfig_clientCanBeClosedWithoutError() {
        ClientConfiguration config = validConfig().build();
        EntitlementsClient client = EntitlementsClientFactory.create(config);

        assertDoesNotThrow(client::close);
    }

    @Test
    void create_validConfigPlaintext_returnsNonNullClient() {
        ClientConfiguration config = validConfig()
                .useTls(false)
                .build();

        EntitlementsClient client = EntitlementsClientFactory.create(config);

        assertNotNull(client);
        client.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ClientConfiguration.Builder validConfig() {
        return ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token");
    }
}
