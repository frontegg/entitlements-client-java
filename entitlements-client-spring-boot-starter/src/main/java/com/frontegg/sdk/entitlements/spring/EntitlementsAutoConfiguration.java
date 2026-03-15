package com.frontegg.sdk.entitlements.spring;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.config.ConsistencyPolicy;
import com.frontegg.sdk.entitlements.fallback.StaticFallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the Frontegg Entitlements Client.
 *
 * <p>Auto-creates an {@link EntitlementsClient} bean when:
 * <ul>
 *   <li>{@code frontegg.entitlements.enabled=true} (default)</li>
 *   <li>{@code frontegg.entitlements.engine-endpoint} is set</li>
 *   <li>No existing {@code EntitlementsClient} bean is present</li>
 * </ul>
 *
 * @since 0.2.0
 */
@AutoConfiguration
@ConditionalOnClass(EntitlementsClient.class)
@ConditionalOnProperty(prefix = "frontegg.entitlements", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EntitlementsProperties.class)
public class EntitlementsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EntitlementsClient entitlementsClient(EntitlementsProperties properties) {
        if (properties.getEngineEndpoint() == null || properties.getEngineEndpoint().isBlank()) {
            throw new IllegalStateException(
                    "frontegg.entitlements.engine-endpoint must be set to create an EntitlementsClient bean");
        }
        if (properties.getEngineToken() == null || properties.getEngineToken().isBlank()) {
            throw new IllegalStateException(
                    "frontegg.entitlements.engine-token must be set to create an EntitlementsClient bean");
        }
        ClientConfiguration.Builder builder = ClientConfiguration.builder()
                .engineEndpoint(properties.getEngineEndpoint())
                .engineToken(properties.getEngineToken())
                .useTls(properties.isUseTls())
                .requestTimeout(properties.getRequestTimeout())
                .bulkRequestTimeout(properties.getBulkRequestTimeout())
                .maxRetries(properties.getMaxRetries())
                .monitoring(properties.isMonitoring())
                .consistencyPolicy(ConsistencyPolicy.valueOf(
                        properties.getConsistencyPolicy().toUpperCase()));

        if (properties.getFallbackResult() != null) {
            builder.fallbackStrategy(new StaticFallback(properties.getFallbackResult()));
        }

        if (properties.getCache() != null) {
            builder.cacheConfiguration(new CacheConfiguration(
                    properties.getCache().getMaxSize(),
                    properties.getCache().getExpireAfterWrite()));
        }

        return EntitlementsClientFactory.create(builder.build());
    }
}
