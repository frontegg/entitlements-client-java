package com.frontegg.sdk.entitlements.model;

/**
 * Identifies <em>who</em> is being checked for entitlements.
 *
 * <p>{@code SubjectContext} is a sealed interface; all permitted implementations are defined in
 * this package. Callers can exhaustively switch over all cases using a Java 17+ pattern-matching
 * {@code switch} expression without a default branch:
 *
 * <pre>{@code
 * switch (subjectContext) {
 *     case UserSubjectContext u   -> handleUser(u);
 *     case EntitySubjectContext e -> handleEntity(e);
 * }
 * }</pre>
 *
 * <p>Permitted implementations:
 * <ul>
 *   <li>{@link UserSubjectContext} — a Frontegg user within a tenant</li>
 *   <li>{@link EntitySubjectContext} — an arbitrary entity for FGA (fine-grained authorization)
 *       checks</li>
 * </ul>
 *
 * @since 0.1.0
 */
public sealed interface SubjectContext permits UserSubjectContext, EntitySubjectContext {
}
