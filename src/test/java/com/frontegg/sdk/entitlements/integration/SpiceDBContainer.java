package com.frontegg.sdk.entitlements.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers wrapper for SpiceDB.
 *
 * <p>Starts SpiceDB in plaintext gRPC mode with a fixed pre-shared key. Used exclusively
 * for integration tests — requires Docker to be running on the host.
 */
public class SpiceDBContainer extends GenericContainer<SpiceDBContainer> {

    private static final int GRPC_PORT = 50051;
    private static final String DEFAULT_PRESHARED_KEY = "integration-test-key";

    public SpiceDBContainer() {
        super(DockerImageName.parse("authzed/spicedb:latest"));
        withExposedPorts(GRPC_PORT);
        // In SpiceDB v1.x+, TLS is disabled when no --grpc-tls-cert-path is provided,
        // and the in-memory datastore is used when no --datastore-engine is specified.
        // The serve example in the help text confirms: just --grpc-preshared-key is sufficient
        // for a no-TLS, in-memory test server.
        withCommand("serve", "--grpc-preshared-key", DEFAULT_PRESHARED_KEY);
        // SpiceDB logs a ready message once the gRPC server is accepting connections.
        waitingFor(Wait.forLogMessage(".*grpc server started.*", 1));
    }

    /**
     * Returns the host:port of the mapped gRPC endpoint for use in {@link
     * com.frontegg.sdk.entitlements.config.ClientConfiguration}.
     */
    public String getGrpcEndpoint() {
        return getHost() + ":" + getMappedPort(GRPC_PORT);
    }

    /**
     * Returns the pre-shared bearer token used to authenticate both the schema writer and
     * the entitlements client.
     */
    public String getPresharedKey() {
        return DEFAULT_PRESHARED_KEY;
    }
}
