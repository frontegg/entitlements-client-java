package com.frontegg.sdk.entitlements.internal;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * gRPC {@link CallCredentials} that attaches a Bearer token to every outbound RPC call.
 *
 * <p>The token is read from the supplied {@link Supplier} on each call, enabling credential
 * rotation without recreating the channel or client.
 *
 * <p>This class is package-private; it is created only by {@link
 * com.frontegg.sdk.entitlements.EntitlementsClientFactory}.
 */
class BearerTokenCallCredentials extends CallCredentials {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> tokenSupplier;

    BearerTokenCallCredentials(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
                                     MetadataApplier applier) {
        try {
            Metadata headers = new Metadata();
            headers.put(AUTHORIZATION_KEY, "Bearer " + tokenSupplier.get());
            applier.apply(headers);
        } catch (Exception e) {
            applier.fail(Status.UNAUTHENTICATED.withCause(e)
                    .withDescription("Failed to obtain bearer token"));
        }
    }

    @Override
    public void thisUsesUnstableApi() {
        // intentionally empty — required by gRPC CallCredentials contract
    }
}
