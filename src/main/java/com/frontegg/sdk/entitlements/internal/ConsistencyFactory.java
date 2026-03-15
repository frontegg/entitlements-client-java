package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.Consistency;
import com.frontegg.sdk.entitlements.config.ConsistencyPolicy;

import java.util.function.Supplier;

/**
 * Package-private helper that converts a {@link ConsistencyPolicy} enum value into a
 * protobuf {@link Consistency} supplier.
 *
 * <p>For {@link ConsistencyPolicy#MINIMIZE_LATENCY} and {@link ConsistencyPolicy#FULLY_CONSISTENT}
 * the returned supplier always returns the same pre-built instance (zero allocation per call).
 */
final class ConsistencyFactory {

    private static final Consistency MINIMIZE_LATENCY =
            Consistency.newBuilder().setMinimizeLatency(true).build();

    private static final Consistency FULLY_CONSISTENT =
            Consistency.newBuilder().setFullyConsistent(true).build();

    private ConsistencyFactory() {
    }

    /**
     * Returns a supplier that produces the {@link Consistency} protobuf for the given policy.
     *
     * @param policy the consistency policy; must not be {@code null}
     * @return a non-null supplier; the returned {@code Consistency} object is safe to reuse
     */
    static Supplier<Consistency> supplierFor(ConsistencyPolicy policy) {
        return switch (policy) {
            case MINIMIZE_LATENCY -> () -> MINIMIZE_LATENCY;
            case FULLY_CONSISTENT -> () -> FULLY_CONSISTENT;
        };
    }
}
