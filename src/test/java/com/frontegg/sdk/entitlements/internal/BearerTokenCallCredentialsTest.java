package com.frontegg.sdk.entitlements.internal;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BearerTokenCallCredentials}.
 *
 * <p>Tests verify that Bearer tokens are correctly attached to gRPC call metadata and that
 * supplier exceptions are properly handled.
 */
class BearerTokenCallCredentialsTest {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    // -------------------------------------------------------------------------
    // Test double: captures metadata applier calls
    // -------------------------------------------------------------------------

    /**
     * Simple test double implementation of {@link CallCredentials.MetadataApplier} that
     * captures method invocations for assertion.
     */
    private static class TestMetadataApplier extends CallCredentials.MetadataApplier {
        Metadata appliedMetadata;
        Status failedStatus;

        @Override
        public void apply(Metadata headers) {
            this.appliedMetadata = headers;
        }

        @Override
        public void fail(Status status) {
            this.failedStatus = status;
        }
    }

    // -------------------------------------------------------------------------
    // Bearer token application
    // -------------------------------------------------------------------------

    @Test
    void applyRequestMetadata_appendsBearerTokenToMetadata() {
        TestMetadataApplier applier = new TestMetadataApplier();
        Supplier<String> tokenSupplier = () -> "test-token-123";
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);
        Executor executor = MoreExecutors.directExecutor();

        credentials.applyRequestMetadata(null, executor, applier);

        assertNotNull(applier.appliedMetadata, "metadata must be applied");
        assertNull(applier.failedStatus, "failure must not be invoked on success");

        String authHeader = applier.appliedMetadata.get(AUTHORIZATION_KEY);
        assertNotNull(authHeader, "authorization header must be present");
        assertEquals("Bearer test-token-123", authHeader, "must use Bearer prefix");
    }

    @Test
    void applyRequestMetadata_usesCurrentTokenFromSupplier() {
        TestMetadataApplier applier1 = new TestMetadataApplier();
        TestMetadataApplier applier2 = new TestMetadataApplier();
        Supplier<String> tokenSupplier = new Supplier<String>() {
            private int callCount = 0;

            @Override
            public String get() {
                callCount++;
                return "token-" + callCount;
            }
        };
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);
        Executor executor = MoreExecutors.directExecutor();

        // First invocation
        credentials.applyRequestMetadata(null, executor, applier1);
        String authHeader1 = applier1.appliedMetadata.get(AUTHORIZATION_KEY);
        assertEquals("Bearer token-1", authHeader1, "first call should use token from first invocation");

        // Second invocation
        credentials.applyRequestMetadata(null, executor, applier2);
        String authHeader2 = applier2.appliedMetadata.get(AUTHORIZATION_KEY);
        assertEquals("Bearer token-2", authHeader2, "second call should use fresh token from supplier");
    }

    @Test
    void applyRequestMetadata_supplierThrowsException_callsFailWithUnauthenticatedStatus() {
        TestMetadataApplier applier = new TestMetadataApplier();
        RuntimeException supplierException = new RuntimeException("Token fetch failed");
        Supplier<String> tokenSupplier = () -> {
            throw supplierException;
        };
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);
        Executor executor = MoreExecutors.directExecutor();

        credentials.applyRequestMetadata(null, executor, applier);

        assertNull(applier.appliedMetadata, "apply must not be called on supplier failure");
        assertNotNull(applier.failedStatus, "fail must be called on supplier exception");
        assertEquals(Status.Code.UNAUTHENTICATED, applier.failedStatus.getCode(),
                "status code must be UNAUTHENTICATED");
        assertTrue(applier.failedStatus.getDescription().contains("Failed to obtain bearer token"),
                "description must mention token failure");
    }

    @Test
    void applyRequestMetadata_supplierNullToken_appendsNullAsTokenValue() {
        TestMetadataApplier applier = new TestMetadataApplier();
        Supplier<String> tokenSupplier = () -> null;
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);
        Executor executor = MoreExecutors.directExecutor();

        credentials.applyRequestMetadata(null, executor, applier);

        assertNotNull(applier.appliedMetadata);
        String authHeader = applier.appliedMetadata.get(AUTHORIZATION_KEY);
        // Supplier returns null, so the header value becomes "Bearer null"
        assertEquals("Bearer null", authHeader);
    }

    @Test
    void applyRequestMetadata_emptyToken_appendsEmptyTokenValue() {
        TestMetadataApplier applier = new TestMetadataApplier();
        Supplier<String> tokenSupplier = () -> "";
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);
        Executor executor = MoreExecutors.directExecutor();

        credentials.applyRequestMetadata(null, executor, applier);

        assertNotNull(applier.appliedMetadata);
        String authHeader = applier.appliedMetadata.get(AUTHORIZATION_KEY);
        assertEquals("Bearer ", authHeader, "empty token results in 'Bearer ' (with trailing space)");
    }

    // -------------------------------------------------------------------------
    // Exception handling
    // -------------------------------------------------------------------------

    @Test
    void applyRequestMetadata_supplierThrowsNullPointerException_propagatesAsUnauthenticatedFailure() {
        TestMetadataApplier applier = new TestMetadataApplier();
        Supplier<String> tokenSupplier = () -> {
            throw new NullPointerException("Token supplier error");
        };
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);
        Executor executor = MoreExecutors.directExecutor();

        credentials.applyRequestMetadata(null, executor, applier);

        assertNotNull(applier.failedStatus);
        assertEquals(Status.Code.UNAUTHENTICATED, applier.failedStatus.getCode());
    }

    @Test
    void thisUsesUnstableApi_doesNotThrow() {
        Supplier<String> tokenSupplier = () -> "token";
        BearerTokenCallCredentials credentials = new BearerTokenCallCredentials(tokenSupplier);

        // Should not throw
        credentials.thisUsesUnstableApi();
    }
}
