package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.PermissionsServiceGrpc;
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.exception.ConfigurationInvalidException;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Package-private factory that constructs the internal gRPC channel and
 * {@link SpiceDBEntitlementsClient}.
 *
 * <p>This class is the only place that instantiates internal types. It is called exclusively by
 * {@link com.frontegg.sdk.entitlements.EntitlementsClientFactory}, which forwards its validated
 * {@link ClientConfiguration} here.
 */
public final class InternalClientFactory {

    private InternalClientFactory() {
        throw new AssertionError("InternalClientFactory must not be instantiated");
    }

    /**
     * Builds a {@link ManagedChannel}, wires {@link BearerTokenCallCredentials}, and returns a
     * fully constructed {@link SpiceDBEntitlementsClient}.
     *
     * @param configuration validated, non-null configuration
     * @return a ready-to-use {@link EntitlementsClient}
     */
    public static EntitlementsClient create(ClientConfiguration configuration) {
        String endpoint = configuration.getEngineEndpoint();
        String host;
        int port;

        int colonIdx = endpoint.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < endpoint.length() - 1) {
            host = endpoint.substring(0, colonIdx);
            try {
                port = Integer.parseInt(endpoint.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                throw new ConfigurationInvalidException(
                        "Invalid port in engineEndpoint '" + endpoint + "': " + e.getMessage());
            }
        } else {
            host = endpoint;
            port = configuration.isUseTls() ? 443 : 50051;
        }

        NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(16 * 1024 * 1024);

        if (configuration.isUseTls()) {
            channelBuilder.negotiationType(NegotiationType.TLS);
        } else {
            channelBuilder.usePlaintext();
        }

        ManagedChannel channel = channelBuilder.build();

        BearerTokenCallCredentials credentials =
                new BearerTokenCallCredentials(configuration.getEngineToken());

        PermissionsServiceGrpc.PermissionsServiceBlockingStub stub =
                PermissionsServiceGrpc.newBlockingStub(channel)
                        .withCallCredentials(credentials);

        return new SpiceDBEntitlementsClient(channel, stub, configuration);
    }
}
