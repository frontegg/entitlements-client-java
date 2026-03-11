# Project Brief: Entitlements Client Java SDK

## Executive Summary

A Java SDK (`entitlements-client-java`) that provides Java/JVM applications with a client for Frontegg's ReBAC (Relationship-Based Access Control) authorization engine, backed by SpiceDB. The SDK mirrors the API and functionality of the existing TypeScript SDK (`@frontegg/e10s-client`), enabling Java backend services to perform entitlement checks (features, permissions, fine-grained authorization) and lookup operations against the authorization engine.

The primary problem being solved is the lack of a Java client for Frontegg's entitlements engine, forcing Java-based customers to build ad-hoc integrations or be unable to use Frontegg's authorization capabilities from JVM services.

## Problem Statement

Frontegg's entitlements engine currently only has a TypeScript/Node.js SDK. Customers and internal services running on the JVM (Spring Boot, Micronaut, Quarkus, plain Java) have no official client library to integrate with the authorization engine. This creates:

- **Adoption friction**: Java-based organizations cannot easily adopt Frontegg's entitlements system
- **Inconsistent integrations**: Teams building ad-hoc gRPC clients risk incompatible implementations (e.g., Base64 encoding differences, incorrect caveat handling)
- **Maintenance burden**: Without an official SDK, each consumer must independently track SpiceDB API changes

The existing TypeScript SDK (`@frontegg/e10s-client`) is ~1,061 lines of production code with a clean, well-tested architecture that can serve as a direct reference for the Java implementation.

## Proposed Solution

Build a Java 17 SDK that:

- **Mirrors the TypeScript SDK's API** — same method names (`isEntitledTo`, `lookupTargetEntities`, `lookupEntities`), same domain types (`SubjectContext`, `RequestContext`, `EntitlementsResult`), same configuration model
- **Uses idiomatic Java patterns** — sealed interfaces for union types, records for DTOs, builder pattern for configuration, `CompletableFuture` for async
- **Minimizes maintenance** — Maven build (stable, declarative), minimal dependencies, automated CI/CD with semantic-release, Dependabot for dependency updates
- **Publishes to Maven Central** — standard distribution for Java libraries

Key differentiator: wire-compatible with the TypeScript SDK (same Base64 encoding, same SpiceDB entity types, same query semantics), ensuring both SDKs work interchangeably against the same authorization engine.

## Target Users

### Primary: Java Backend Developers (Frontegg Customers)

- Building Spring Boot / Micronaut / Quarkus backend services
- Need to check feature entitlements, permissions, or fine-grained authorization in their application layer
- Expect Maven Central distribution, builder-pattern APIs, SLF4J logging, and Spring Boot auto-configuration
- Value minimal dependency footprint and clear Javadoc

### Secondary: Internal Frontegg Engineering

- Services being built or migrated to JVM
- Need an official, tested client rather than ad-hoc gRPC integration
- Require consistency with the TypeScript SDK's behavior

## Goals & Success Metrics

### Business Objectives

- Enable Java-based customers to integrate Frontegg entitlements within one sprint
- Reduce support tickets related to ad-hoc Java integrations
- Expand Frontegg's addressable market to JVM-based organizations

### User Success Metrics

- Time from `mvn dependency:add` to first successful entitlement check < 15 minutes
- Zero wire-compatibility issues between Java and TypeScript SDKs
- SDK works out-of-the-box with Spring Boot 3.x without additional configuration

### Key Performance Indicators

- Maven Central weekly downloads (target: measurable adoption within 90 days)
- GitHub issues opened (leading indicator of usage)
- Production deployments (tracked via optional `X-Frontegg-SDK: java/x.y.z` gRPC metadata header)

## MVP Scope

### Core Features (Must Have)

- **Feature entitlement checks**: `isEntitledTo(userContext, featureContext)` — check if a user/tenant can access a named feature
- **Permission entitlement checks**: `isEntitledTo(userContext, permissionContext)` — check if a user has specific permission(s)
- **FGA entity checks**: `isEntitledTo(entityContext, entityRequestContext)` — check if one entity can perform an action on another
- **Fallback configuration**: Static fallback value or custom function on error
- **Monitoring mode**: Log authorization results but always return success
- **Factory + Builder pattern**: `EntitlementsClientFactory.create(config)` and `EntitlementsClient.builder()...build()`
- **Async + Sync API**: `CompletableFuture<EntitlementsResult>` async with blocking sync wrapper
- **SLF4J logging**: Pluggable logging with structured debug output
- **Maven Central publishing**: Automated via GitHub Actions + semantic-release

### Out of Scope for MVP

- **Route entitlement checks** — Most complex query type (regex matching, priority-based evaluation, cached relationship data). Deferred to Phase 2.
- **Lookup operations** (`lookupTargetEntities`, `lookupEntities`) — Phase 2
- **Caching** (Caffeine-based with `CacheProvider` interface) — Phase 2
- **Time-based access** (`at` caveat parameter) — Phase 2
- **Spring Boot starter** auto-configuration module — Phase 2
- **Test utilities module** (mock client, recording spy) — Phase 2
- **Metrics/observability** (Micrometer integration) — Phase 2

### MVP Success Criteria

- All Phase 1 entitlement checks produce identical results to the TypeScript SDK against the same SpiceDB instance
- Cross-language Base64 encoding compatibility verified with explicit test cases
- Published to Maven Central with Javadoc, source JARs, and GPG signatures
- README with quickstart, Maven/Gradle dependency snippets, and code examples

## Post-MVP Vision

### Phase 2 Features

- Route entitlement checks (complex regex matching + caching)
- Lookup operations (lookupTargetEntities, lookupEntities)
- Caffeine caching behind `CacheProvider` interface with configurable TTL
- Time-based access (`at` parameter for temporal entitlements)
- Spring Boot starter module (`entitlements-client-spring-boot-starter`)
- Test utilities module (mock client, test assertions)

### Long-term Vision

- Full feature parity with TypeScript SDK
- Reactive API support (Project Reactor `Mono`/`Flux`) as optional module
- Kotlin extensions module for idiomatic Kotlin usage
- GraalVM native-image compatibility
- OpenTelemetry tracing integration (auto-detected when OTel is on classpath)

### Expansion Opportunities

- Kotlin-first SDK variant
- Android-specific lightweight client (HTTP-based, not gRPC)
- Quarkus/Micronaut-specific extension modules

## Technical Considerations

### Platform Requirements

- **Target JVM**: Java 17+ (LTS, required by Spring Boot 3.x, enables records + sealed classes)
- **Tested against**: Java 17, 21
- **Performance**: gRPC-based, single shared `ManagedChannel` with HTTP/2 multiplexing
- **Thread safety**: Fully thread-safe client, designed for singleton use in DI containers

### Technology Stack

- **Language**: Java 17
- **Build**: Maven
- **SpiceDB client**: `com.authzed.api:authzed` (authzed-java, official gRPC client)
- **gRPC transport**: `grpc-netty-shaded` (already included as runtimeOnly by authzed-java v1.5.4 — relocated Netty, no classpath conflicts)
- **Caching** (Phase 2): Caffeine behind `CacheProvider` interface
- **Logging**: SLF4J API
- **Testing**: JUnit 5, Mockito, SLF4J Simple (test scope)
- **Publishing**: Maven Central via Sonatype Central Publisher Portal (`central.sonatype.com`, using `central-publishing-maven-plugin`)
- **CI/CD**: GitHub Actions
- **Versioning**: semantic-release (conventional commits)

### Architecture Considerations

- **Repository**: Single repo, single Maven module (Phase 2 adds spring-boot-starter as sub-module)
- **Package structure**: `com.frontegg.sdk.entitlements.*` with `internal` sub-package for non-public APIs
- **API design**: Sealed interfaces for union types, records for DTOs, builder for config
- **Error handling**: Unchecked exception hierarchy rooted at `EntitlementsException`
- **Resource management**: `AutoCloseable` client, JVM shutdown hook for channel cleanup

## Constraints & Assumptions

### Constraints

- **Budget**: Internal engineering effort, no external cost beyond Maven Central setup
- **Timeline**: Phase 1 target — production-ready within 2 sprints
- **Resources**: Single developer + AI-assisted implementation
- **Technical**: Must use `authzed-java` gRPC client (wire compatibility with TS SDK's `authzed-node`)

### Key Assumptions

- The SpiceDB schema and entity types (`frontegg_user`, `frontegg_tenant`, `frontegg_permission`, `frontegg_feature`) are stable and shared between TS and Java SDKs
- The TypeScript SDK's config field naming will settle on `engineEndpoint`/`engineToken` (not the older `spiceDBEndpoint`/`spiceDBToken`)
- Frontegg has or will set up a Sonatype OSSRH account for `com.frontegg.sdk` namespace
- Target customers are primarily Spring Boot 3.2+ applications. Spring Boot 2.x (Java 8/11) is EOL and not supported. If Java 11 demand emerges, evaluate a compat module — do not build preemptively.

## Risks & Open Questions

### Key Risks

- **Dependency conflicts**: `authzed-java` v1.5.4 brings grpc-java 1.78.0, protobuf-java 4.33.5, and `grpc-netty-shaded` (relocated Netty — no classpath conflicts). Risk is reduced but not eliminated: consumers with other protobuf-based libraries (Google Cloud, Couchbase) may hit version mismatches. Mitigation: publish BOM with `grpc-bom` and `protobuf-bom`, test against Spring Boot 3.2/3.3/3.4.
- **Base64 encoding mismatch**: `normalizeObjectId` must produce identical output across TypeScript and Java. A mismatch causes silent authorization failures. Mitigation: explicit cross-language compatibility tests.
- **authzed-java maintenance**: If the official Java client becomes unmaintained, the SDK inherits that risk. Contingency: generate gRPC stubs directly from SpiceDB protobuf definitions.
- **Maven Central namespace**: Frontegg has an existing account. Need to verify namespace is claimed on the new Central Publisher Portal (`central.sonatype.com`) after OSSRH sunset migration.

### Open Questions

- ~~Is the Sonatype OSSRH account for `com.frontegg.sdk` already set up?~~ → Frontegg has an account. OSSRH is sunset; migrated to Central Publisher Portal (`central.sonatype.com`). Need to verify `com.frontegg.sdk` namespace is claimed there.
- ~~Should the Java SDK version independently or attempt to track the TS SDK version?~~ → **Version independently.** Synced versions create false expectations and prevent Java-specific patches. Compatibility matrix in README maps SDK versions to engine versions.
- ~~Are there specific Spring Boot versions we must support beyond 3.x?~~ → Latest stable: Spring Boot 3.2, 3.3, 3.4
- ~~Do any customers need Android support?~~ → No. Java backend only, not Android.

### Areas Needing Further Research

- authzed-java's exact transitive dependency versions and compatibility with Spring Boot 3.2/3.3 BOMs
- Whether `grpc-netty-shaded` fully eliminates Netty conflicts in practice
- SpiceDB proto stability guarantees for `CheckBulkPermissions` API

## Appendices

### A. Reference Implementation

The TypeScript SDK (`@frontegg/e10s-client`) serves as the reference implementation:
- Repository: https://github.com/frontegg/entitlements-client
- ~1,061 lines of production code, 13 test spec files
- Key files: `spicedb-entitlements.client.ts`, `entitlements-spicedb.query.ts`, `client-configuration.ts`

### C. References

- [SpiceDB Documentation](https://authzed.com/docs)
- [authzed-java GitHub](https://github.com/authzed/authzed-java)
- [Frontegg TypeScript SDK](https://github.com/frontegg/entitlements-client)
- [Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-guide/)

## Next Steps

1. Create PRD with detailed functional/non-functional requirements
2. Create Architecture document with package structure, API contracts, and coding standards
3. Define epic and stories for implementation
4. Begin Sonatype OSSRH namespace verification for `com.frontegg.sdk`
5. Implement Phase 1
