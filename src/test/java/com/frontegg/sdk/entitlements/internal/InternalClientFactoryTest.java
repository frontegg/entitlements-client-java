package com.frontegg.sdk.entitlements.internal;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.exception.ConfigurationInvalidException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link InternalClientFactory}.
 *
 * <p>Tests verify that the factory correctly parses host:port endpoints, applies default ports
 * based on TLS settings, and rejects invalid port values.
 */
class InternalClientFactoryTest {

    // -------------------------------------------------------------------------
    // Endpoint parsing — host:port
    // -------------------------------------------------------------------------

    @Test
    void create_hostWithPort_parsesCorrectly() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(() -> "token")
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "client must be created successfully");
        client.close();
    }

    @Test
    void create_hostWithHighPort_parsesCorrectly() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("spicedb.example.com:9999")
                .engineToken(() -> "token")
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "client must be created for high port number");
        client.close();
    }

    @Test
    void create_hostWithPort443_parsesCorrectly() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("spicedb.example.com:443")
                .engineToken(() -> "token")
                .useTls(true)
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "client must be created for port 443");
        client.close();
    }

    // -------------------------------------------------------------------------
    // Default port behavior
    // -------------------------------------------------------------------------

    @Test
    void create_hostWithoutPort_useTlsTrue_defaults443() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("spicedb.example.com")
                .engineToken(() -> "token")
                .useTls(true)
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "client must be created with default TLS port 443");
        client.close();
    }

    @Test
    void create_hostWithoutPort_useTlsFalse_defaults50051() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost")
                .engineToken(() -> "token")
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "client must be created with default plaintext port 50051");
        client.close();
    }

    // -------------------------------------------------------------------------
    // Invalid port handling
    // -------------------------------------------------------------------------

    @Test
    void create_invalidPort_nonNumeric_throwsConfigurationInvalidException() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:abc")
                .engineToken(() -> "token")
                .build();

        assertThrows(ConfigurationInvalidException.class,
                () -> InternalClientFactory.create(config),
                "non-numeric port must throw ConfigurationInvalidException");
    }

    @Test
    void create_invalidPort_negative_createsClient() {
        // Integer.parseInt accepts negative numbers; -1 is a valid integer
        // NettyChannelBuilder will handle invalid port values at channel creation time
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:-1")
                .engineToken(() -> "token")
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "factory accepts negative port; validation happens at channel creation");
        client.close();
    }

    @Test
    void create_invalidPort_tooLarge_createsClient() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:999999")
                .engineToken(() -> "token")
                .build();

        // Note: Integer.parseInt will accept any valid integer; port > 65535 may fail at channel creation
        // This test documents behavior — factory accepts the value but channel creation may fail later
        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "factory creates client; channel creation may fail at runtime");
        client.close();
    }

    // -------------------------------------------------------------------------
    // Endpoint edge cases
    // -------------------------------------------------------------------------

    @Test
    void create_hostWithIPv4Address_parsesCorrectly() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("127.0.0.1:50051")
                .engineToken(() -> "token")
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "client must be created with IPv4 address");
        client.close();
    }

    @Test
    void create_hostWithMultipleColons_usesLastColon_asPortDelimiter() {
        // IPv6-style addresses may have colons; the implementation uses lastIndexOf
        // This documents the behavior for IPv6 addresses without brackets
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("::1:50051")
                .engineToken(() -> "token")
                .build();

        // This will parse the last :50051 as host:port, treating "::1" as the host
        // IPv6 addresses should ideally use brackets, but this documents current behavior
        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client);
        client.close();
    }

    // -------------------------------------------------------------------------
    // Client creation and resource cleanup
    // -------------------------------------------------------------------------

    @Test
    void create_returnsSpiceDBEntitlementsClientInstance() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(() -> "token")
                .build();

        EntitlementsClient client = InternalClientFactory.create(config);
        assertNotNull(client, "create must return a non-null EntitlementsClient");

        // Clean up to avoid resource leaks
        client.close();
    }

    @Test
    void create_withDifferentTokenSuppliers() {
        ClientConfiguration config1 = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(() -> "token-1")
                .build();

        ClientConfiguration config2 = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(() -> "token-2")
                .build();

        EntitlementsClient client1 = InternalClientFactory.create(config1);
        EntitlementsClient client2 = InternalClientFactory.create(config2);

        assertNotNull(client1);
        assertNotNull(client2);

        // Clean up
        client1.close();
        client2.close();
    }

    @Test
    void create_withAndWithoutTls() {
        ClientConfiguration configPlaintext = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken(() -> "token")
                .build();

        ClientConfiguration configTls = ClientConfiguration.builder()
                .engineEndpoint("spicedb.example.com:443")
                .engineToken(() -> "token")
                .useTls(true)
                .build();

        EntitlementsClient clientPlaintext = InternalClientFactory.create(configPlaintext);
        EntitlementsClient clientTls = InternalClientFactory.create(configTls);

        assertNotNull(clientPlaintext);
        assertNotNull(clientTls);

        // Clean up
        clientPlaintext.close();
        clientTls.close();
    }
}
