# Entitlements Client Java SDK — Product Requirements Document (PRD)

## Goals and Background Context

### Goals

- Provide Java/JVM applications an official, production-grade client for Frontegg's ReBAC authorization engine
- Mirror the TypeScript SDK's API surface so developers familiar with the TS SDK can adopt the Java SDK with minimal learning curve
- Minimize ongoing maintenance through automated publishing, Dependabot, and minimal dependency surface
- Publish to Maven Central as the standard distribution channel for Java libraries
- Achieve wire-compatibility with the TypeScript SDK (identical Base64 encoding, SpiceDB entity types, query semantics)

### Background Context

Frontegg's entitlements engine uses SpiceDB as the authorization backend, with the TypeScript SDK (`@frontegg/e10s-client`) as the only official client. The Java ecosystem — particularly Spring Boot, which dominates enterprise backend development — has no official client. This forces Java-based customers into ad-hoc gRPC integrations that risk encoding mismatches, incorrect caveat handling, and ongoing maintenance burden.

The TypeScript SDK is well-structured (~1,061 LOC, strategy pattern, factory + configuration) and serves as a direct reference. The Java SDK will port this functionality using idiomatic Java 17 patterns (sealed interfaces, records, builders) while maintaining behavioral compatibility.

### Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-03-11 | 0.1 | Initial PRD draft | AI-assisted |

---

## Requirements

### Functional

- **FR1**: The SDK shall provide an `isEntitledTo(SubjectContext, RequestContext)` method that checks whether a subject is entitled to a given feature, permission, or entity action, returning an `EntitlementsResult`.

- **FR2**: The SDK shall support `FeatureRequestContext` — checking if a user/tenant has access to a named feature key. This maps to SpiceDB `CheckBulkPermissions` with `frontegg_feature` resource type.

- **FR3**: The SDK shall support `PermissionRequestContext` — checking if a user has one or more named permissions. This checks both local permission existence and SpiceDB feature-linked permissions.

- **FR4**: The SDK shall support `EntityRequestContext` (FGA) — checking if one entity can perform a relation/action on another entity via SpiceDB `CheckPermission`.

- **FR5**: The SDK shall provide both synchronous (`EntitlementsResult`) and asynchronous (`CompletableFuture<EntitlementsResult>`) variants of `isEntitledTo`.

- **FR6**: The SDK shall accept a `ClientConfiguration` via builder pattern with required fields `engineEndpoint` and `engineToken`, and optional fields for fallback strategy, request timeout, and logging configuration.

- **FR7**: The SDK shall validate configuration on client creation and throw `ConfigurationMissingException` if required fields are absent, with actionable error messages indicating which field is missing.

- **FR8**: The SDK shall support configurable fallback behavior via `FallbackStrategy` — either a static boolean result (`StaticFallback`) or a custom function (`FunctionFallback`) invoked on authorization engine errors.

- **FR9**: The SDK shall support monitoring mode — when enabled on a `RequestContext`, the SDK logs the authorization result but always returns `EntitlementsResult(true, monitoring=true)`.

- **FR10**: The SDK shall encode object IDs using URL-safe Base64 without padding (`Base64.getUrlEncoder().withoutPadding()` with UTF-8 input), producing identical output to the TypeScript SDK's `normalizeObjectId` function.

- **FR11**: The SDK shall construct SpiceDB caveat context for user attributes, including `active_at` timestamps when provided.

- **FR12**: The SDK shall provide a factory method `EntitlementsClientFactory.create(ClientConfiguration)` that validates configuration and returns an `EntitlementsClient` instance.

- **FR13**: The SDK shall implement `AutoCloseable` to enable proper gRPC channel shutdown via try-with-resources or explicit `close()` calls.

- **FR14** (Phase 2): The SDK shall support `RouteRequestContext` — checking if a user can access an HTTP method+path combination, including regex pattern matching and priority-based allow/deny evaluation against cached route relationships.

- **FR15** (Phase 2): The SDK shall provide `lookupTargetEntities(LookupTargetEntitiesRequest)` — finding all resources a subject can access for a given relation.

- **FR16** (Phase 2): The SDK shall provide `lookupEntities(LookupEntitiesRequest)` — finding all subjects that have a given relation to a target resource.

- **FR17** (Phase 2): The SDK shall provide a `CacheProvider<K,V>` interface with a default Caffeine implementation, allowing consumers to plug in custom cache backends.

- **FR18** (Phase 2): The SDK shall support time-based access via an `at` parameter (ISO 8601 timestamp) in request contexts, passed as a caveat to SpiceDB.

### Non Functional

- **NFR1**: The SDK shall target Java 17 as the minimum JVM version and be tested against Java 17 and 21.

- **NFR2**: The SDK shall depend on `authzed-java` v1.5.2+ which already uses `grpc-netty-shaded` as a runtime dependency. The SDK's BOM shall include `grpc-bom` and `protobuf-bom` for transitive dependency alignment.

- **NFR3**: The SDK shall use SLF4J as the logging facade, with no hard dependency on any logging implementation.

- **NFR4**: The SDK shall be fully thread-safe — the client instance is designed for singleton use in DI containers with concurrent access from multiple threads.

- **NFR5**: The SDK shall use a single shared `ManagedChannel` per client instance with HTTP/2 multiplexing for all gRPC calls.

- **NFR6**: The SDK shall configure gRPC deadlines on every RPC call (default 5s for `CheckPermission`, 15s for `CheckBulkPermissions`), with configurable timeout via `ClientConfiguration`.

- **NFR7**: The SDK shall expose all DTOs as immutable types (Java records) with no mutable state crossing API boundaries.

- **NFR8**: The SDK shall publish to Maven Central with Javadoc JAR, source JAR, and GPG signatures.

- **NFR9**: The SDK shall have automated CI (build + test on PRs) and CD (publish on tag) via GitHub Actions.

- **NFR10**: The SDK shall maintain test coverage of at least 70% for statements, branches, functions, and lines.

- **NFR11**: The SDK shall produce unchecked exceptions only — no checked exceptions in the public API.

- **NFR12**: The SDK shall include Javadoc on all public classes, interfaces, methods, and constructors.

- **NFR13**: The SDK shall provide a Bill of Materials (BOM) POM to help consumers align transitive dependency versions.

- **NFR14**: The SDK shall implement exponential backoff with jitter for retryable gRPC status codes (`UNAVAILABLE`, `DEADLINE_EXCEEDED`), capped at 3 retries with max 2s backoff.

- **NFR15**: The SDK shall document minimum version requirements (Java 17, protobuf 4.28+) and known compatibility constraints with common frameworks (Spring Boot 3.2+, Quarkus 3.x, Micronaut 4.x) in the README and BOM module.

- **NFR16**: The SDK shall never log, include in exception messages, or expose via `toString()` the `engineToken` value. Token redaction must be enforced at DEBUG and TRACE log levels.

- **NFR17**: The SDK shall version independently from the TypeScript SDK. Versions follow semantic versioning via conventional commits. A compatibility matrix in the README maps SDK versions to supported entitlements engine versions.

---

## Technical Assumptions

### Repository Structure

Single Maven repository with a single module for Phase 1. Phase 2 introduces additional modules:

```
entitlements-client-java/          (parent POM)
├── entitlements-client/           (core SDK)
├── entitlements-client-bom/       (BOM POM, Phase 1)
├── entitlements-client-spring/    (Spring Boot starter, Phase 2)
└── entitlements-client-test/      (Test utilities, Phase 2)
```

### Service Architecture

This is a client library, not a service. It wraps gRPC calls to an external SpiceDB instance. No server components, no database, no message queues.

### Testing Requirements

- **Unit tests**: JUnit 5 + Mockito for all query strategies, factory validation, fallback handling, encoding utilities
- **Cross-language compatibility tests**: Verify Base64 encoding output matches TypeScript SDK for a set of known inputs
- **Integration tests** (Phase 2): Testcontainers + SpiceDB Docker image for end-to-end verification
- Coverage target: 70%+ across statements, branches, functions, lines

### Additional Technical Assumptions

- Maven is the build tool (not Gradle) for long-term stability and minimal build toolchain maintenance
- The SDK will NOT shade/relocate dependencies — instead, it uses `grpc-netty-shaded` and publishes a BOM for version alignment
- No Spring, Micronaut, or framework dependencies in the core module
- The `authzed-java` client is stable enough for production use; contingency is generating gRPC stubs from SpiceDB protobufs
- `Supplier<String>` pattern for engine token enables credential rotation without client reinstantiation
- **Java 17 is the minimum** — Spring Boot 2.x (Java 8/11) is EOL and not supported. If customer demand for Java 11 emerges, evaluate a separate `entitlements-client-compat` module with a reduced API (no records/sealed classes). Do not build preemptively.
- **Supported framework versions**: Spring Boot 3.2+, Quarkus 3.x, Micronaut 4.x. Older versions are unsupported.

---

## Epic List

### Epic 1: Project Foundation & Core Client

Establish the Maven project, CI/CD pipelines, public API types, and the core `isEntitledTo` flow for Feature and Permission checks — delivering a minimal but functional SDK that can be built, tested, and published.

### Epic 2: FGA, Fallback & Resilience

Add Fine-Grained Authorization (entity) checks, configurable fallback strategies, monitoring mode, retry logic, and proper error handling — completing the Phase 1 feature set.

### Epic 3: Publishing & Documentation

Configure Maven Central publishing, create the BOM module, write README with quickstart examples, and ensure all public APIs have Javadoc — making the SDK ready for external consumption.

### Epic 4: Lookup Operations & Caching (Phase 2)

Add `lookupTargetEntities`, `lookupEntities`, Caffeine caching behind `CacheProvider` interface, and time-based access (`at` parameter).

### Epic 5: Route Checks & Spring Boot Starter (Phase 2)

Implement the complex route entitlement checks (regex matching, priority evaluation, cached relationships) and the Spring Boot starter auto-configuration module.

---

## Epic 1: Project Foundation & Core Client

**Goal**: Establish the project infrastructure (Maven POM, CI workflows, package structure) and implement the core entitlement check flow for Features and Permissions. By the end of this epic, the SDK can be built, tested, and the `isEntitledTo` method works for `FeatureRequestContext` and `PermissionRequestContext` against a real SpiceDB instance.

### Story 1.1: Maven Project Scaffold & CI Pipeline

As a **developer**,
I want the project scaffolded with Maven, directory structure, .gitignore, and GitHub Actions CI,
so that I have a working build pipeline from the first commit.

**Acceptance Criteria:**
1. `pom.xml` exists with Java 17 source/target, correct groupId/artifactId/version (0.1.0-SNAPSHOT)
2. Dependencies declared: `authzed-java`, `slf4j-api`, JUnit 5, Mockito (test scope)
3. `grpc-netty-shaded` is used (not `grpc-netty`)
4. Directory structure created: `src/main/java/com/frontegg/sdk/entitlements/` and all sub-packages (`model`, `config`, `fallback`, `cache`, `strategy`, `exception`, `internal`)
5. `.gitignore` covers Java/Maven/IntelliJ artifacts
6. `.github/workflows/ci.yaml` runs `mvn verify` on PRs and pushes to main, with Java 17 and 21 matrix
7. `mvn compile` succeeds with no errors

### Story 1.2: Public API Types & Interfaces

As a **developer**,
I want all public API types (interfaces, records, sealed types, exceptions) defined,
so that the SDK's API contract is established before implementation begins.

**Acceptance Criteria:**
1. `EntitlementsClient` interface defined with `isEntitledTo` (sync + async) and `close()` methods
2. `SubjectContext` sealed interface with `UserSubjectContext` and `EntitySubjectContext` record implementations
3. `RequestContext` sealed interface with `FeatureRequestContext`, `PermissionRequestContext`, `RouteRequestContext`, `EntityRequestContext` record implementations
4. `EntitlementsResult` record with `result` and `monitoring` fields, plus `allowed()`/`denied()` factory methods
5. `ClientConfiguration` class with builder pattern (engineEndpoint, engineToken, fallbackStrategy, requestTimeout, cacheConfiguration)
6. `FallbackStrategy` sealed interface with `StaticFallback` and `FunctionFallback` implementations
7. `FallbackContext` record with subjectContext, requestContext, and error fields
8. `CacheProvider<K,V>` interface with get/put/invalidate/invalidateAll methods
9. Exception hierarchy: `EntitlementsException`, `ConfigurationMissingException`, `ConfigurationInvalidException`, `EntitlementsQueryException`, `EntitlementsTimeoutException`
10. All public types have Javadoc
11. `mvn compile` succeeds

### Story 1.3: Client Factory & Configuration Validation

As a **developer**,
I want `EntitlementsClientFactory.create(config)` to validate configuration and create a gRPC channel,
so that invalid configurations fail fast with clear error messages.

**Acceptance Criteria:**
1. `EntitlementsClientFactory.create(config)` validates `engineEndpoint` and `engineToken` are non-null/non-empty
2. Missing `engineEndpoint` throws `ConfigurationMissingException` with message: "engineEndpoint is required. Set via ClientConfiguration.builder().engineEndpoint(...)"
3. Missing `engineToken` throws `ConfigurationMissingException` with similar actionable message
4. Valid configuration creates a `ManagedChannel` with `grpc-netty-shaded` transport and TLS enabled by default
5. Channel is configured with keepAlive (30s time, 10s timeout) and max inbound message size (16MB)
6. Factory returns an `EntitlementsClient` instance that holds the channel
7. Unit tests verify all validation paths and error messages
8. Test coverage for factory: >90%

### Story 1.4: Feature Entitlement Check (SpiceDB Integration)

As a **developer**,
I want `isEntitledTo(userContext, featureRequestContext)` to check feature entitlements via SpiceDB,
so that applications can verify if a user/tenant has access to a named feature.

**Acceptance Criteria:**
1. `FeatureSpiceDBQuery` strategy handles `FeatureRequestContext`
2. Constructs SpiceDB `CheckBulkPermissions` request with subject `frontegg_user:<base64(userId)>` and resource `frontegg_feature:<base64(featureKey)>` with relation `entitled`
3. Also checks tenant-level: subject `frontegg_tenant:<base64(tenantId)>` with same resource/relation
4. Base64 encoding uses `Base64.getUrlEncoder().withoutPadding()` with UTF-8 input bytes
5. Returns `EntitlementsResult(true, false)` if either user or tenant check succeeds
6. Returns `EntitlementsResult(false, false)` if both checks fail
7. Caveat context includes user attributes from `UserSubjectContext.attributes()`
8. `SpiceDBQueryClient` dispatches `FeatureRequestContext` to `FeatureSpiceDBQuery`
9. Unit tests with mocked authzed-java client verify request construction and result mapping
10. Cross-language test: verify Base64 output for known inputs matches TypeScript SDK output

### Story 1.5: Permission Entitlement Check

As a **developer**,
I want `isEntitledTo(userContext, permissionRequestContext)` to check permission entitlements via SpiceDB,
so that applications can verify if a user has specific permissions.

**Acceptance Criteria:**
1. `PermissionSpiceDBQuery` strategy handles `PermissionRequestContext`
2. For each permission key, constructs SpiceDB `CheckBulkPermissions` request checking both user and tenant subjects against `frontegg_permission:<base64(permissionKey)>` with relation `entitled`
3. Supports multiple permission keys in a single request (bulk check)
4. Returns `EntitlementsResult(true, false)` if ALL requested permissions are granted
5. Returns `EntitlementsResult(false, false)` if any permission is denied
6. `SpiceDBQueryClient` dispatches `PermissionRequestContext` to `PermissionSpiceDBQuery`
7. Unit tests verify single and multiple permission checks, partial denial, and full grant scenarios
8. Async variant (`isEntitledToAsync`) works correctly and returns `CompletableFuture`

---

## Epic 2: FGA, Fallback & Resilience

**Goal**: Complete the Phase 1 feature set by adding Fine-Grained Authorization (entity-to-entity) checks, configurable fallback strategies, monitoring mode, and retry/error handling. After this epic, the SDK handles all Phase 1 entitlement types with production-grade resilience.

### Story 2.1: FGA Entity Entitlement Check

As a **developer**,
I want `isEntitledTo(entityContext, entityRequestContext)` to check entity-to-entity permissions,
so that applications can verify fine-grained authorization (e.g., "can user X edit document Y").

**Acceptance Criteria:**
1. `FgaSpiceDBQuery` strategy handles `EntityRequestContext`
2. Constructs SpiceDB `CheckPermission` request with subject `<entityContext.entityType>:<base64(entityContext.entityId)>` and resource `<entityRequestContext.resourceType>:<base64(entityRequestContext.resourceId)>` with relation from `entityRequestContext.relation()`
3. Returns `EntitlementsResult(true, false)` if permission is granted
4. Returns `EntitlementsResult(false, false)` if permission is denied
5. `SpiceDBQueryClient` dispatches `EntityRequestContext` to `FgaSpiceDBQuery`
6. Unit tests verify request construction and result mapping for grant and deny cases

### Story 2.2: Fallback Strategy Implementation

As a **developer**,
I want the SDK to apply configurable fallback behavior when SpiceDB calls fail,
so that applications can degrade gracefully during authorization engine outages.

**Acceptance Criteria:**
1. When a gRPC call fails and `StaticFallback` is configured, `isEntitledTo` returns `EntitlementsResult(staticValue, false)` instead of throwing
2. When a gRPC call fails and `FunctionFallback` is configured, the SDK invokes the function with `FallbackContext(subjectContext, requestContext, error)` and returns the function's result
3. When no fallback is configured, gRPC errors are wrapped in `EntitlementsQueryException` (or `EntitlementsTimeoutException` for deadline exceeded) and thrown
4. Fallback is NOT applied for configuration errors (`ConfigurationMissingException`, etc.) — those always throw
5. If the fallback function itself throws, the original gRPC error is wrapped in `EntitlementsQueryException` with the fallback error as a suppressed exception
6. Unit tests verify all fallback paths: static true, static false, function success, function error, no fallback

### Story 2.3: Monitoring Mode

As a **developer**,
I want monitoring mode to log the real authorization result but always return success,
so that teams can observe entitlement behavior before enforcing it.

**Acceptance Criteria:**
1. When `monitoring=true` on the request context, the SDK performs the actual SpiceDB check
2. Logs the real result at INFO level: "Monitoring mode: isEntitledTo({subject}, {request}) = {realResult}"
3. Returns `EntitlementsResult(true, monitoring=true)` regardless of the real result
4. If the SpiceDB call fails in monitoring mode, logs the error at WARN level and returns `EntitlementsResult(true, monitoring=true)`
5. Unit tests verify monitoring mode logs correctly and always returns true

### Story 2.4: Retry Logic & Error Handling

As a **developer**,
I want the SDK to retry transient gRPC failures with exponential backoff,
so that brief network issues don't cause unnecessary authorization failures.

**Acceptance Criteria:**
1. `UNAVAILABLE` and `DEADLINE_EXCEEDED` gRPC status codes trigger retry
2. Retries use exponential backoff with jitter: base 200ms, factor 2, max backoff 2s
3. Maximum 3 retry attempts (configurable via `ClientConfiguration`)
4. `PERMISSION_DENIED`, `UNAUTHENTICATED`, `INVALID_ARGUMENT` are NOT retried — immediately wrapped and thrown/fallback
5. gRPC `StatusRuntimeException` is wrapped in `EntitlementsQueryException` with the original exception as cause
6. `DEADLINE_EXCEEDED` is wrapped in `EntitlementsTimeoutException` specifically
7. Error messages include the endpoint URL and gRPC status code for debuggability
8. Unit tests verify retry behavior, non-retryable codes, and correct exception wrapping

### Story 2.5: Resource Lifecycle & Shutdown

As a **developer**,
I want the client to properly manage gRPC channel lifecycle,
so that there are no resource leaks in long-running applications.

**Acceptance Criteria:**
1. `EntitlementsClient.close()` calls `ManagedChannel.shutdown()` followed by `awaitTermination(5, SECONDS)`
2. If channel doesn't terminate cleanly, `shutdownNow()` is called and a WARN log is emitted
3. Client works with try-with-resources: `try (var client = factory.create(config)) { ... }`
4. Calling `isEntitledTo` after `close()` throws `IllegalStateException("Client has been closed")`
5. `close()` is idempotent — calling it multiple times does not throw
6. Unit tests verify shutdown sequence and post-close behavior

---

## Epic 3: Publishing & Documentation

**Goal**: Make the SDK ready for external consumption by configuring Maven Central publishing, creating the BOM, writing documentation, and ensuring all public APIs have complete Javadoc.

### Story 3.1: Maven Central Publishing Pipeline

As a **developer**,
I want automated publishing to Maven Central via GitHub Actions,
so that new versions are released automatically on tagged commits.

**Acceptance Criteria:**
1. `pom.xml` has a `release` profile with `maven-gpg-plugin`, `maven-javadoc-plugin`, `maven-source-plugin`, `central-publishing-maven-plugin` (Sonatype Central Portal — replaces deprecated `nexus-staging-maven-plugin`)
2. `.github/workflows/publish.yaml` triggers on `v*` tags
3. Workflow builds, signs (GPG), and deploys to Maven Central via the Central Publisher Portal (`central.sonatype.com`)
4. Uses GitHub secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (portal token), `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`
5. `.releaserc.yaml` configured for semantic-release with Angular preset and `main` branch

### Story 3.2: BOM Module

As a **consumer developer**,
I want a Bill of Materials POM for the SDK,
so that I can align all transitive dependency versions without conflicts.

**Acceptance Criteria:**
1. `entitlements-client-bom` module exists with `<packaging>pom</packaging>`
2. BOM declares `<dependencyManagement>` for: authzed-java, grpc-java, protobuf-java, Caffeine, SLF4J
3. Consumers can import the BOM via `<scope>import</scope>` in their `<dependencyManagement>`
4. BOM version matches the SDK version (published together)

### Story 3.3: README & Quickstart Documentation

As a **consumer developer**,
I want a comprehensive README with quickstart examples,
so that I can integrate the SDK in under 15 minutes.

**Acceptance Criteria:**
1. README includes: project description, Maven/Gradle dependency snippets, quickstart code example
2. Quickstart shows: create config → create client → check feature → check permission → check FGA entity
3. Includes both sync and async usage examples
4. Documents configuration options (builder methods, environment variables)
5. Documents error handling and fallback configuration
6. Documents monitoring mode
7. Includes compatibility matrix: Java versions, Spring Boot versions tested
8. Links to Javadoc (published)

### Story 3.4: Javadoc Audit & Completion

As a **consumer developer**,
I want all public APIs documented with Javadoc,
so that IDE autocomplete and generated documentation are helpful.

**Acceptance Criteria:**
1. Every public class, interface, record, method, and constructor has Javadoc
2. Javadoc includes `@param`, `@return`, `@throws` tags where applicable
3. Key methods (`isEntitledTo`, factory methods) include `<pre>` code examples in Javadoc
4. `@since 0.1.0` tag on all public types
5. `mvn javadoc:javadoc` generates clean output with no warnings

---

## Epic 4: Lookup Operations & Caching (Phase 2)

**Goal**: Add lookup operations and caching to enable resource discovery and improve performance for repeated entitlement checks.

### Story 4.1: CacheProvider Interface & Caffeine Implementation

### Story 4.2: Lookup Target Entities

### Story 4.3: Lookup Entities

### Story 4.4: Time-Based Access (`at` Parameter)

---

## Epic 5: Route Checks & Spring Boot Starter (Phase 2)

**Goal**: Implement the complex route entitlement check and provide Spring Boot auto-configuration for seamless framework integration.

### Story 5.1: Route Entitlement Check

### Story 5.2: Spring Boot Starter Auto-Configuration

### Story 5.3: Test Utilities Module

---

## Next Steps

### Architect Prompt

Review this PRD and the Project Brief (`docs/brief.md`) to create the Architecture document. Focus on: package structure, API contracts with full method signatures, SpiceDB query construction details, gRPC channel configuration, error handling flow, and coding standards. Output to `docs/architecture.md`.
