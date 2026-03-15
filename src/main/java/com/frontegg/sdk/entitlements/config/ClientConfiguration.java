package com.frontegg.sdk.entitlements.config;

import com.frontegg.sdk.entitlements.exception.ConfigurationMissingException;
import com.frontegg.sdk.entitlements.fallback.FallbackStrategy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable configuration for creating an
 * {@link com.frontegg.sdk.entitlements.EntitlementsClient}.
 *
 * <p>Use the fluent {@link Builder} to construct an instance. The builder validates all required
 * fields in {@link Builder#build()} and throws a descriptive
 * {@link ConfigurationMissingException} for any missing required field.
 *
 * <pre>{@code
 * ClientConfiguration config = ClientConfiguration.builder()
 *         .engineEndpoint("grpc.authz.example.com:443")
 *         .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
 *         .requestTimeout(Duration.ofSeconds(3))
 *         .fallbackStrategy(new StaticFallback(false))
 *         .build();
 * }</pre>
 *
 * <p>This class is immutable and thread-safe. The engine token is accessed via a
 * {@link Supplier} to enable credential rotation without recreating the client.
 *
 * @since 0.1.0
 */
public final class ClientConfiguration {

    /** Default timeout for single-resource entitlement checks. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default timeout for bulk entitlement checks. */
    public static final Duration DEFAULT_BULK_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Default maximum number of retry attempts for transient failures. */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** Default consistency policy for SpiceDB reads. */
    public static final ConsistencyPolicy DEFAULT_CONSISTENCY_POLICY = ConsistencyPolicy.MINIMIZE_LATENCY;

    private final String engineEndpoint;
    private final Supplier<String> engineToken;
    private final FallbackStrategy fallbackStrategy;
    private final Duration requestTimeout;
    private final Duration bulkRequestTimeout;
    private final int maxRetries;
    private final boolean useTls;
    private final boolean monitoring;
    private final CacheConfiguration cacheConfiguration;
    private final ConsistencyPolicy consistencyPolicy;

    private ClientConfiguration(Builder builder) {
        this.engineEndpoint = builder.engineEndpoint;
        this.engineToken = builder.engineToken;
        this.fallbackStrategy = builder.fallbackStrategy;
        this.requestTimeout = builder.requestTimeout;
        this.bulkRequestTimeout = builder.bulkRequestTimeout;
        this.maxRetries = builder.maxRetries;
        this.useTls = builder.useTls;
        this.monitoring = builder.monitoring;
        this.cacheConfiguration = builder.cacheConfiguration;
        this.consistencyPolicy = builder.consistencyPolicy;
    }

    /**
     * Returns a new {@link Builder} for constructing a {@code ClientConfiguration}.
     *
     * @return a fresh builder instance
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the SpiceDB gRPC endpoint URL (e.g. {@code "grpc.authz.example.com:443"}).
     *
     * @return the engine endpoint; never {@code null}
     * @since 0.1.0
     */
    public String getEngineEndpoint() {
        return engineEndpoint;
    }

    /**
     * Returns a {@link Supplier} that provides the bearer token used to authenticate gRPC
     * calls to the authorization engine.
     *
     * <p>The supplier is invoked on every gRPC call to allow credential rotation without
     * recreating the client.
     *
     * @return the engine token supplier; never {@code null}
     * @since 0.1.0
     */
    public Supplier<String> getEngineToken() {
        return engineToken;
    }

    /**
     * Returns the configured fallback strategy, or {@code null} if no fallback is set (in which
     * case exceptions propagate to the caller on failure).
     *
     * @return the fallback strategy, or {@code null}
     * @since 0.1.0
     */
    public FallbackStrategy getFallbackStrategy() {
        return fallbackStrategy;
    }

    /**
     * Returns the timeout applied to individual (non-bulk) entitlement check requests.
     *
     * @return the request timeout; never {@code null}
     * @since 0.1.0
     */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the timeout applied to bulk entitlement check requests.
     *
     * @return the bulk request timeout; never {@code null}
     * @since 0.1.0
     */
    public Duration getBulkRequestTimeout() {
        return bulkRequestTimeout;
    }

    /**
     * Returns the maximum number of retry attempts for transient gRPC failures before the
     * fallback strategy (if any) is invoked or the exception propagates.
     *
     * @return the maximum retry count; always &gt;= 0
     * @since 0.1.0
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Returns {@code true} when TLS is enabled for the gRPC channel (recommended for
     * production), {@code false} for plaintext (useful for local development).
     *
     * @return whether TLS is enabled
     * @since 0.1.0
     */
    public boolean isUseTls() {
        return useTls;
    }

    /**
     * Returns {@code true} when monitoring mode is enabled. In monitoring mode the SDK performs
     * the real SpiceDB check and logs the result, but always returns
     * {@code EntitlementsResult(true, monitoring=true)} to the caller — authorization is never
     * enforced. This lets teams observe entitlement behaviour before enabling enforcement.
     *
     * @return whether monitoring mode is enabled
     * @since 0.1.0
     */
    public boolean isMonitoring() {
        return monitoring;
    }

    /**
     * Returns the optional cache configuration. When non-{@code null}, the SDK will create
     * a {@link com.frontegg.sdk.entitlements.cache.CaffeineCacheProvider} internally to cache
     * entitlement results keyed by (subject, request) pair. When {@code null} (the default),
     * caching is disabled and every call goes directly to the authorization engine.
     *
     * @return the cache configuration, or {@code null} if caching is disabled
     * @since 0.2.0
     */
    public CacheConfiguration getCacheConfiguration() {
        return cacheConfiguration;
    }

    /**
     * Returns the consistency policy for SpiceDB reads.
     *
     * @return the consistency policy; never {@code null}
     * @since 0.2.0
     */
    public ConsistencyPolicy getConsistencyPolicy() {
        return consistencyPolicy;
    }

    /**
     * Returns a string representation of this configuration with the engine token redacted to
     * prevent accidental secret exposure in logs or stack traces.
     *
     * @return a safe string representation; engine token is always shown as {@code [REDACTED]}
     * @since 0.1.0
     */
    @Override
    public String toString() {
        return "ClientConfiguration{"
                + "engineEndpoint=" + engineEndpoint
                + ", engineToken=[REDACTED]"
                + ", fallbackStrategy=" + fallbackStrategy
                + ", requestTimeout=" + requestTimeout
                + ", bulkRequestTimeout=" + bulkRequestTimeout
                + ", maxRetries=" + maxRetries
                + ", useTls=" + useTls
                + ", monitoring=" + monitoring
                + ", cacheConfiguration=" + cacheConfiguration
                + ", consistencyPolicy=" + consistencyPolicy
                + '}';
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link ClientConfiguration}.
     *
     * <p>All setter methods follow the convention of using the field name directly (no
     * {@code set} prefix) and returning {@code this} for chaining.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private String engineEndpoint;
        private Supplier<String> engineToken;
        private FallbackStrategy fallbackStrategy;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration bulkRequestTimeout = DEFAULT_BULK_REQUEST_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private boolean useTls = true;
        private boolean monitoring = false;
        private CacheConfiguration cacheConfiguration; // null = caching disabled
        private ConsistencyPolicy consistencyPolicy = DEFAULT_CONSISTENCY_POLICY;

        private Builder() {
        }

        /**
         * Sets the SpiceDB gRPC endpoint URL.
         *
         * @param engineEndpoint the endpoint (e.g. {@code "grpc.authz.example.com:443"});
         *                       must not be null or blank
         * @return this builder
         * @since 0.1.0
         */
        public Builder engineEndpoint(String engineEndpoint) {
            this.engineEndpoint = engineEndpoint;
            return this;
        }

        /**
         * Sets a static bearer token for authenticating gRPC calls.
         *
         * <p>The token is wrapped in a constant {@link Supplier}. For dynamic credential
         * rotation prefer {@link #engineToken(Supplier)}.
         *
         * @param token the bearer token; must not be null or blank
         * @return this builder
         * @since 0.1.0
         */
        public Builder engineToken(String token) {
            Objects.requireNonNull(token, "token must not be null");
            this.engineToken = () -> token;
            return this;
        }

        /**
         * Sets a dynamic {@link Supplier} for the bearer token. The supplier is called on
         * every gRPC request, enabling credential rotation without recreating the client.
         *
         * @param tokenSupplier a supplier that returns the current token; must not be null
         * @return this builder
         * @since 0.1.0
         */
        public Builder engineToken(Supplier<String> tokenSupplier) {
            this.engineToken = Objects.requireNonNull(tokenSupplier,
                    "tokenSupplier must not be null");
            return this;
        }

        /**
         * Sets the fallback strategy to invoke when all retries are exhausted.
         *
         * <p>When no fallback is configured (the default), exceptions propagate to the caller.
         *
         * @param fallbackStrategy the fallback strategy; may be {@code null} to disable
         * @return this builder
         * @since 0.1.0
         */
        public Builder fallbackStrategy(FallbackStrategy fallbackStrategy) {
            this.fallbackStrategy = fallbackStrategy;
            return this;
        }

        /**
         * Sets the timeout for individual (non-bulk) entitlement check requests.
         *
         * @param requestTimeout the timeout; must not be null and must be positive
         * @return this builder
         * @since 0.1.0
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout,
                    "requestTimeout must not be null");
            if (requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            return this;
        }

        /**
         * Sets the timeout for bulk entitlement check requests.
         *
         * @param bulkRequestTimeout the timeout; must not be null and must be positive
         * @return this builder
         * @since 0.1.0
         */
        public Builder bulkRequestTimeout(Duration bulkRequestTimeout) {
            this.bulkRequestTimeout = Objects.requireNonNull(bulkRequestTimeout,
                    "bulkRequestTimeout must not be null");
            if (bulkRequestTimeout.isNegative() || bulkRequestTimeout.isZero()) {
                throw new IllegalArgumentException("bulkRequestTimeout must be positive");
            }
            return this;
        }

        /**
         * Sets the maximum number of retry attempts for transient gRPC failures.
         *
         * @param maxRetries the maximum retries; must be &gt;= 0
         * @return this builder
         * @since 0.1.0
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Controls whether TLS is enabled for the gRPC channel.
         *
         * @param useTls {@code true} to enable TLS (default), {@code false} for plaintext
         * @return this builder
         * @since 0.1.0
         */
        public Builder useTls(boolean useTls) {
            this.useTls = useTls;
            return this;
        }

        /**
         * Enables or disables monitoring mode. When monitoring mode is active, the SDK performs
         * the real SpiceDB check and logs the result but always returns
         * {@code EntitlementsResult(true, monitoring=true)} — authorization is never enforced.
         *
         * <p>Use this to observe entitlement behaviour in production before enabling
         * enforcement. Default is {@code false}.
         *
         * @param monitoring {@code true} to enable monitoring mode; {@code false} (default) for
         *                   normal enforcement mode
         * @return this builder
         * @since 0.1.0
         */
        public Builder monitoring(boolean monitoring) {
            this.monitoring = monitoring;
            return this;
        }

        /**
         * Enables optional in-memory result caching backed by Caffeine.
         *
         * <p>When set, the SDK creates a {@link com.frontegg.sdk.entitlements.cache.CaffeineCacheProvider}
         * internally and caches every successful (non-monitoring, non-fallback) entitlement result
         * keyed by the (subject, request) pair. Subsequent calls with the same pair return the
         * cached result without reaching the authorization engine until the entry expires.
         *
         * <p>Requires {@code com.github.ben-manes.caffeine:caffeine} on the classpath.
         *
         * <p>Pass {@code null} to disable caching (the default).
         *
         * @param cacheConfiguration the cache configuration; {@code null} disables caching
         * @return this builder
         * @since 0.2.0
         */
        public Builder cacheConfiguration(CacheConfiguration cacheConfiguration) {
            this.cacheConfiguration = cacheConfiguration;
            return this;
        }

        /**
         * Sets the consistency policy for SpiceDB reads.
         *
         * <p>Default is {@link ConsistencyPolicy#MINIMIZE_LATENCY} (SpiceDB's default, best
         * performance). Use {@link ConsistencyPolicy#FULLY_CONSISTENT} when you need
         * read-after-write consistency.
         *
         * @param consistencyPolicy the consistency policy; must not be {@code null}
         * @return this builder
         * @since 0.2.0
         */
        public Builder consistencyPolicy(ConsistencyPolicy consistencyPolicy) {
            this.consistencyPolicy = Objects.requireNonNull(consistencyPolicy,
                    "consistencyPolicy must not be null");
            return this;
        }

        /**
         * Validates the configuration and builds the {@link ClientConfiguration} instance.
         *
         * @return a new, immutable {@code ClientConfiguration}
         * @throws ConfigurationMissingException if {@code engineEndpoint} or
         *                                       {@code engineToken} is not set
         * @since 0.1.0
         */
        public ClientConfiguration build() {
            if (engineEndpoint == null || engineEndpoint.isBlank()) {
                throw new ConfigurationMissingException("engineEndpoint");
            }
            if (engineToken == null) {
                throw new ConfigurationMissingException("engineToken");
            }
            return new ClientConfiguration(this);
        }
    }
}
