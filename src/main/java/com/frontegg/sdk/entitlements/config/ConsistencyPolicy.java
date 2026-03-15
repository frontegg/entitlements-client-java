package com.frontegg.sdk.entitlements.config;

/**
 * Controls the consistency guarantee for SpiceDB reads.
 *
 * <ul>
 *   <li>{@link #MINIMIZE_LATENCY} — SpiceDB's default; fastest, allows stale reads.</li>
 *   <li>{@link #FULLY_CONSISTENT} — linearizable reads; hits the primary datastore on every
 *       request. Use when you need read-after-write consistency (e.g. in tests or after
 *       relationship writes).</li>
 * </ul>
 *
 * @since 0.2.0
 */
public enum ConsistencyPolicy {

    /**
     * SpiceDB's default consistency mode. Allows the engine to serve reads from replicas,
     * providing the lowest latency at the cost of potentially stale data.
     */
    MINIMIZE_LATENCY,

    /**
     * Linearizable consistency. Every read goes to the primary datastore, guaranteeing
     * the most up-to-date result. Higher latency than {@link #MINIMIZE_LATENCY}.
     */
    FULLY_CONSISTENT
}
