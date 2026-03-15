package com.frontegg.sdk.entitlements.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot configuration properties for the Frontegg Entitlements Client.
 *
 * <p>Configure via {@code application.properties} or {@code application.yml}:
 * <pre>
 * frontegg.entitlements.engine-endpoint=grpc.authz.example.com:443
 * frontegg.entitlements.engine-token=${ENTITLEMENTS_ENGINE_TOKEN}
 * frontegg.entitlements.use-tls=true
 * frontegg.entitlements.request-timeout=5s
 * frontegg.entitlements.bulk-request-timeout=15s
 * frontegg.entitlements.max-retries=3
 * frontegg.entitlements.monitoring=false
 * frontegg.entitlements.fallback-result=false
 * frontegg.entitlements.cache.max-size=10000
 * frontegg.entitlements.cache.expire-after-write=60s
 * frontegg.entitlements.consistency-policy=minimize_latency
 * </pre>
 *
 * @since 0.2.0
 */
@ConfigurationProperties(prefix = "frontegg.entitlements")
public class EntitlementsProperties {

    private String engineEndpoint;
    private String engineToken;
    private boolean useTls = true;
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration bulkRequestTimeout = Duration.ofSeconds(15);
    private int maxRetries = 3;
    private boolean monitoring = false;
    private Boolean fallbackResult;  // null = no fallback, true/false = static fallback
    private boolean enabled = true;
    private CacheProperties cache;
    private String consistencyPolicy = "minimize_latency";

    public String getEngineEndpoint() { return engineEndpoint; }
    public void setEngineEndpoint(String engineEndpoint) { this.engineEndpoint = engineEndpoint; }

    public String getEngineToken() { return engineToken; }
    public void setEngineToken(String engineToken) { this.engineToken = engineToken; }

    public boolean isUseTls() { return useTls; }
    public void setUseTls(boolean useTls) { this.useTls = useTls; }

    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }

    public Duration getBulkRequestTimeout() { return bulkRequestTimeout; }
    public void setBulkRequestTimeout(Duration bulkRequestTimeout) { this.bulkRequestTimeout = bulkRequestTimeout; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public boolean isMonitoring() { return monitoring; }
    public void setMonitoring(boolean monitoring) { this.monitoring = monitoring; }

    public Boolean getFallbackResult() { return fallbackResult; }
    public void setFallbackResult(Boolean fallbackResult) { this.fallbackResult = fallbackResult; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public CacheProperties getCache() { return cache; }
    public void setCache(CacheProperties cache) { this.cache = cache; }

    public String getConsistencyPolicy() { return consistencyPolicy; }
    public void setConsistencyPolicy(String consistencyPolicy) { this.consistencyPolicy = consistencyPolicy; }

    /**
     * Nested cache configuration properties.
     */
    public static class CacheProperties {
        private int maxSize = 10_000;
        private Duration expireAfterWrite = Duration.ofSeconds(60);

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

        public Duration getExpireAfterWrite() { return expireAfterWrite; }
        public void setExpireAfterWrite(Duration expireAfterWrite) { this.expireAfterWrite = expireAfterWrite; }
    }
}
