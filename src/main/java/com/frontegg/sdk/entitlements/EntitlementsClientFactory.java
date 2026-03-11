package com.frontegg.sdk.entitlements;

import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.internal.InternalClientFactory;

/**
 * Static factory for creating {@link EntitlementsClient} instances.
 *
 * <p>This is the primary way consumers obtain a configured, ready-to-use client. The factory
 * validates the supplied {@link ClientConfiguration} at creation time and throws a descriptive
 * exception if any required field is missing or invalid, ensuring misconfiguration is caught
 * early rather than at query time.
 *
 * <pre>{@code
 * ClientConfiguration config = ClientConfiguration.builder()
 *         .engineEndpoint("grpc.authz.example.com:443")
 *         .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
 *         .requestTimeout(Duration.ofSeconds(3))
 *         .build();
 *
 * EntitlementsClient client = EntitlementsClientFactory.create(config);
 * }</pre>
 *
 * <p>This class cannot be instantiated; use the static {@link #create} method directly.
 *
 * @see EntitlementsClient
 * @see ClientConfiguration
 * @since 0.1.0
 */
public final class EntitlementsClientFactory {

    /**
     * Private constructor — this is a static factory class and must not be instantiated.
     */
    private EntitlementsClientFactory() {
        throw new AssertionError("EntitlementsClientFactory must not be instantiated");
    }

    /**
     * Creates and returns a new {@link EntitlementsClient} configured with the supplied
     * {@link ClientConfiguration}.
     *
     * <p>The factory performs the following steps:
     * <ol>
     *   <li>Validates that all required configuration fields are present (endpoint, token).</li>
     *   <li>Establishes the underlying gRPC {@code ManagedChannel} to the authorization
     *       engine.</li>
     *   <li>Wires up the query dispatcher, retry handler, and optional fallback strategy.</li>
     *   <li>Returns the fully initialised {@link EntitlementsClient}.</li>
     * </ol>
     *
     * <p>The returned client is thread-safe and should be shared for the lifetime of the
     * application. Call {@link EntitlementsClient#close()} to release the underlying channel
     * when the client is no longer needed.
     *
     * @param configuration the validated client configuration; must not be {@code null}
     * @return a fully initialised, ready-to-use {@link EntitlementsClient}
     * @throws com.frontegg.sdk.entitlements.exception.ConfigurationMissingException if a
     *         required configuration field is absent
     * @throws com.frontegg.sdk.entitlements.exception.ConfigurationInvalidException if a
     *         configuration field has an invalid value
     * @throws IllegalArgumentException if {@code configuration} is {@code null}
     * @since 0.1.0
     */
    public static EntitlementsClient create(ClientConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        return InternalClientFactory.create(configuration);
    }
}
