package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Identifies an arbitrary entity as the subject of a fine-grained authorization (FGA) check.
 *
 * <p>Use this context when the subject is not a Frontegg user but rather a machine principal,
 * service account, or any other typed entity managed in the authorization engine.
 *
 * <pre>{@code
 * SubjectContext subject = new EntitySubjectContext("service_account", "svc-deployer-01");
 * }</pre>
 *
 * @param entityType the type name of the entity as registered in the SpiceDB schema (e.g.
 *                   {@code "service_account"}, {@code "device"}); must not be null or blank
 * @param entityId   the unique identifier of the entity instance; must not be null or blank
 * @since 0.1.0
 */
public record EntitySubjectContext(
        String entityType,
        String entityId
) implements SubjectContext {

    /**
     * Compact canonical constructor — validates that neither field is null nor blank.
     *
     * @throws NullPointerException     if {@code entityType} or {@code entityId} is {@code null}
     * @throws IllegalArgumentException if {@code entityType} or {@code entityId} is blank
     */
    public EntitySubjectContext {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
        if (entityId.isBlank()) {
            throw new IllegalArgumentException("entityId must not be blank");
        }
    }
}
