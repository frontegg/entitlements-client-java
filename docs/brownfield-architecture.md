# Entitlements Client Java SDK — Brownfield Architecture Document

## Introduction

This document captures the **current state** of the `entitlements-client-java` codebase as of v0.1.0-beta.1, including actual patterns, implementation details, technical constraints, and integration points. It serves as a reference for AI agents and developers working on enhancements to the SDK.

This is a **client library** (SDK) — not a service. It wraps Frontegg's SpiceDB-backed ReBAC authorization engine via gRPC, enabling JVM applications to perform real-time permission checks.

### Document Scope

Comprehensive documentation of entire system.

### Change Log

| Date       | Version | Description                     | Author       |
|------------|---------|---------------------------------|--------------|
| 2026-03-11 | 1.0     | Initial brownfield analysis     | AI-assisted  |

---

## Quick Reference — Key Files and Entry Points

### Critical Files for Understanding the System

- **Public API Interface**: `src/main/java/com/frontegg/sdk/entitlements/EntitlementsClient.java`
- **Client Factory**: `src/main/java/com/frontegg/sdk/entitlements/EntitlementsClientFactory.java`
- **Configuration**: `src/main/java/com/frontegg/sdk/entitlements/config/ClientConfiguration.java`
- **Query Dispatcher**: `src/main/java/com/frontegg/sdk/entitlements/internal/SpiceDBQueryClient.java`
- **Strategy Implementations**: `src/main/java/com/frontegg/sdk/entitlements/internal/Feature|Permission|Route|FgaSpiceDBQuery.java`
- **Retry Logic**: `src/main/java/com/frontegg/sdk/entitlements/internal/RetryHandler.java`
- **Cache Layer**: `src/main/java/com/frontegg/sdk/entitlements/cache/CaffeineCacheProvider.java`
- **Fallback Strategies**: `src/main/java/com/frontegg/sdk/entitlements/fallback/FallbackStrategy.java`
- **Spring Boot Auto-Config**: `entitlements-client-spring-boot-starter/src/main/java/.../EntitlementsAutoConfiguration.java`
- **Test Mock**: `entitlements-client-test/src/main/java/.../MockEntitlementsClient.java`

### Build & CI

- **Root POM**: `pom.xml`
- **CI Pipeline**: `.github/workflows/ci.yaml` (matrix: Java 17, 21)
- **Publish Pipeline**: `.github/workflows/publish.yaml` (tag-triggered, Maven Central)
- **Release Config**: `.releaserc.yaml` (semantic-release, conventional commits)

---

## High Level Architecture

### Technical Summary

| Category         | Technology        | Version | Notes                                    |
|------------------|-------------------|---------|------------------------------------------|
| Language         | Java              | 17+     | Sealed interfaces, records used heavily  |
| Build            | Maven             | 3.x     | Multi-module project                     |
| gRPC Transport   | grpc-netty-shaded | 1.78.0  | Shaded Netty for conflict avoidance      |
| gRPC Client      | grpc-stub         | 1.78.0  | gRPC stubs for SpiceDB API               |
| SpiceDB Client   | authzed-java      | 1.5.4   | Authzed's official Java client           |
| Protobuf         | protobuf-java     | 4.33.5  | Protocol Buffers runtime                 |
| Caching          | Caffeine          | 3.1.8   | Optional dependency, LRU + TTL           |
| Logging          | SLF4J             | 2.0.16  | Facade only — no runtime binding shipped |
| Spring Boot      | autoconfigure     | 3.2.0   | Provided scope in starter module         |
| Testing          | JUnit Jupiter     | 5.10.3  | Unit tests                               |
| Testing          | Mockito           | 5.14.2  | Mocking framework                        |
| Testing          | Testcontainers    | 1.19.8  | Docker-based SpiceDB integration tests   |

### Repository Structure

Type: **Multi-module Maven project**

```text
entitlements-client-java/
├── src/                                          # Core SDK (44 Java source files)
│   ├── main/java/com/frontegg/sdk/entitlements/
│   │   ├── EntitlementsClient.java               # Public API interface (sealed)
│   │   ├── EntitlementsClientFactory.java         # Static factory, validates config
│   │   ├── config/
│   │   │   ├── ClientConfiguration.java           # Builder pattern, immutable
│   │   │   └── CacheConfiguration.java            # Cache settings (maxSize, TTL)
│   │   ├── model/
│   │   │   ├── SubjectContext.java                 # Sealed: User | Entity subject
│   │   │   ├── RequestContext.java                 # Sealed: Feature | Permission | Route | Entity
│   │   │   ├── EntitlementsResult.java             # Record: result + monitoring flag
│   │   │   ├── LookupResult.java                   # Record: list of entity IDs
│   │   │   ├── LookupResourcesRequest.java         # Lookup resources request
│   │   │   └── LookupSubjectsRequest.java          # Lookup subjects request
│   │   ├── exception/
│   │   │   ├── EntitlementsException.java           # Base exception
│   │   │   ├── EntitlementsQueryException.java      # SpiceDB gRPC errors
│   │   │   ├── EntitlementsTimeoutException.java    # DEADLINE_EXCEEDED
│   │   │   ├── ConfigurationMissingException.java   # Required field absent
│   │   │   └── ConfigurationInvalidException.java   # Invalid field value
│   │   ├── fallback/
│   │   │   ├── FallbackStrategy.java                # Sealed interface
│   │   │   ├── StaticFallback.java                  # Fixed true/false result
│   │   │   ├── FunctionFallback.java                # Custom function handler
│   │   │   └── FallbackContext.java                 # Context passed to handler
│   │   ├── cache/
│   │   │   ├── CacheProvider.java                   # Generic K,V cache interface
│   │   │   └── CaffeineCacheProvider.java           # Caffeine-backed implementation
│   │   └── internal/                               # Package-private implementation
│   │       ├── SpiceDBEntitlementsClient.java       # Core client implementation
│   │       ├── SpiceDBQueryClient.java              # Strategy dispatcher
│   │       ├── InternalClientFactory.java           # gRPC channel factory
│   │       ├── FeatureSpiceDBQuery.java             # Feature check (OR semantics)
│   │       ├── PermissionSpiceDBQuery.java          # Permission check (AND semantics)
│   │       ├── RouteSpiceDBQuery.java               # Route check (regex matching)
│   │       ├── FgaSpiceDBQuery.java                 # FGA check (single relation)
│   │       ├── LookupSpiceDBQuery.java              # Lookup operations (streaming)
│   │       ├── RetryHandler.java                    # Exponential backoff retry
│   │       ├── BearerTokenCallCredentials.java      # gRPC auth credentials
│   │       ├── CaveatContextBuilder.java            # Attribute map → Protobuf Struct
│   │       ├── Base64Utils.java                     # URL-safe Base64 (no padding)
│   │       └── EntitlementsCacheKey.java            # Cache key record
│   └── test/java/com/frontegg/sdk/entitlements/    # 20 test files
│       ├── config/                                  # Configuration tests
│       ├── internal/                                # Strategy & client tests
│       ├── cache/                                   # Cache behavior tests
│       └── integration/                             # SpiceDB Testcontainers tests
├── entitlements-client-bom/                        # Bill of Materials (version alignment)
│   └── pom.xml
├── entitlements-client-spring-boot-starter/         # Spring Boot auto-configuration
│   └── src/main/java/.../
│       ├── EntitlementsAutoConfiguration.java
│       └── EntitlementsProperties.java
├── entitlements-client-test/                        # Test utilities for consumers
│   └── src/main/java/.../
│       ├── MockEntitlementsClient.java              # Configurable mock
│       └── RecordingEntitlementsClient.java         # Spy wrapper
├── .github/workflows/
│   ├── ci.yaml                                     # Build + test matrix
│   └── publish.yaml                                # Maven Central publish
├── pom.xml                                         # Root POM
├── .releaserc.yaml                                 # Semantic-release config
└── docs/                                           # Documentation
```

### Published Maven Artifacts (4)

| Artifact                                    | Type | Consumers          | Contains                                           |
|---------------------------------------------|------|--------------------|----------------------------------------------------
| `com.frontegg.sdk:entitlements-client`      | jar  | All JVM apps       | Core gRPC client, config, models, cache, fallback  |
| `com.frontegg.sdk:entitlements-client-bom`  | pom  | Multi-SDK projects | Version alignment for all artifacts + dependencies |
| `com.frontegg.sdk:entitlements-client-spring-boot-starter` | jar | Spring Boot apps | Auto-configuration + properties binding |
| `com.frontegg.sdk:entitlements-client-test` | jar  | Test suites        | MockEntitlementsClient, RecordingEntitlementsClient |

---

## Source Tree and Module Organization

### Key Modules and Their Purpose

#### Core SDK (`src/main/java`)
The heart of the library. 44 source files organized into public API surface (`EntitlementsClient`, `EntitlementsClientFactory`, `config.*`, `model.*`, `exception.*`, `fallback.*`, `cache.*`) and package-private implementation (`internal.*`).

**Design principle**: All `internal` classes are package-private. Consumers interact only through:
- `EntitlementsClient` interface (sealed)
- `EntitlementsClientFactory.create(config)` factory method
- Immutable records and sealed interfaces for models

#### Spring Boot Starter (`entitlements-client-spring-boot-starter/`)
Auto-configuration that creates an `EntitlementsClient` bean from `application.yml` properties. Activated by default when on classpath; disabled via `frontegg.entitlements.enabled=false`.

#### Test Utilities (`entitlements-client-test/`)
Two test doubles for consumers:
- `MockEntitlementsClient` — configurable mock (default: all denied), records calls
- `RecordingEntitlementsClient` — spy that wraps a real client and records interactions

#### BOM (`entitlements-client-bom/`)
Manages versions of all four Frontegg artifacts plus transitive dependencies (gRPC, Protobuf, authzed-java, SLF4J, Caffeine).

---

## Architecture Patterns — Actual Implementation

### 1. Strategy Pattern — Query Dispatch

`SpiceDBQueryClient` routes requests based on `RequestContext` type via sealed interface pattern matching:

| RequestContext Type      | Strategy Class           | SpiceDB RPC Used         | Semantics        |
|--------------------------|--------------------------|--------------------------|------------------|
| `FeatureRequestContext`  | `FeatureSpiceDBQuery`    | `checkBulkPermissions`   | OR (user OR tenant) |
| `PermissionRequestContext` | `PermissionSpiceDBQuery` | `checkBulkPermissions` | AND (all permissions) |
| `RouteRequestContext`    | `RouteSpiceDBQuery`      | `checkBulkPermissions`   | Regex route match |
| `EntityRequestContext`   | `FgaSpiceDBQuery`        | `checkPermission`        | Single relation  |

**Key detail**: Feature checks send 2 items (user + tenant) and return true if **either** is entitled. Permission checks send 2N items (N permissions x 2 subjects) and return true only if **all** are entitled.

### 2. gRPC Channel Management

`InternalClientFactory` creates a single shared `ManagedChannel`:
- Uses `NettyChannelBuilder` (shaded transport)
- Keep-alive: 30s interval, 10s timeout
- Max inbound message: 16MB
- Port defaults: 443 (TLS) / 50051 (plaintext)
- `BearerTokenCallCredentials` reads token from `Supplier<String>` on every call (supports rotation)

### 3. Retry with Exponential Backoff

`RetryHandler` retries only transient gRPC errors:
- **Retryable**: `UNAVAILABLE`, `DEADLINE_EXCEEDED`
- **Non-retryable**: All others (fail immediately)
- **Backoff formula**: `min(200ms * 2^attempt, 2000ms) + random(0-100ms)`
- **Max retries**: Configurable (default 3)

### 4. Caching Layer

`CaffeineCacheProvider` provides optional in-memory caching:
- **Key**: `EntitlementsCacheKey(subjectContext, requestContext)` — composite record
- **Defaults**: 10,000 max entries, 60s TTL (write-time expiry)
- **Rule**: Only caches successful, non-monitoring results
- **Dependency**: Caffeine is `<optional>true</optional>` — consumers must add it explicitly

### 5. Fallback Strategy (Sealed)

When all retries are exhausted:
- `StaticFallback(boolean)` — always returns fixed result
- `FunctionFallback(Function<FallbackContext, Boolean>)` — custom logic with full context (subject, request, cause)
- No fallback configured → exception propagates to caller
- **Monitoring mode bypasses fallback** (always returns allowed)

### 6. Monitoring Mode

Non-enforcement observability:
- Executes the real SpiceDB check
- Logs result at INFO level
- **Always returns** `EntitlementsResult(true, monitoring=true)` regardless of actual result
- Fallback is not invoked in monitoring mode

### 7. Base64 Encoding

All SpiceDB object IDs are Base64-encoded before sending and decoded on return:
- **RFC 4648 Section 5** — URL-safe alphabet (`-_` instead of `+/`)
- **No padding** — matches the TypeScript SDK's `normalizeObjectId`
- Cross-SDK compatibility requirement

### 8. Caveat Context Builder

Converts user attributes `Map<String, Object>` + optional `Instant` into Protobuf `Struct`:
- Supports: String, Number, Boolean, null
- Unsupported types silently skipped
- Time parameter formatted as ISO-8601 string under key `"at"`
- Returns `null` (not empty Struct) when nothing to encode — SpiceDB treats them differently

---

## Data Models and APIs

### Subject Context Hierarchy (Sealed)

```java
SubjectContext (sealed)
├── UserSubjectContext(String userId, String tenantId, Map<String, Object> attributes)
│   // Encodes as: frontegg_user:<base64(userId)> + frontegg_tenant:<base64(tenantId)>
└── EntitySubjectContext(String entityType, String entityId)
    // Encodes as: <entityType>:<base64(entityId)>
```

### Request Context Hierarchy (Sealed)

```java
RequestContext (sealed)
├── FeatureRequestContext(String featureKey, Instant at)
│   // Checks: frontegg_feature:<base64(featureKey)>#entitled
├── PermissionRequestContext(List<String> permissionKeys, Instant at)
│   // Checks: frontegg_permission:<base64(key)>#entitled for each key
├── RouteRequestContext(String method, String path, Instant at)
│   // Checks: frontegg_route:<base64(method:path)>#entitled
└── EntityRequestContext(String resourceType, String resourceId, String relation)
    // Checks: <resourceType>:<base64(resourceId)>#<relation>
```

### Result Models (Records)

- `EntitlementsResult(boolean result, boolean monitoring)` — entitlement check result
- `LookupResult(List<String> entityIds)` — immutable list of matched IDs
- `LookupResourcesRequest(String subjectType, String subjectId, String permission, String resourceType)`
- `LookupSubjectsRequest(String resourceType, String resourceId, String permission, String subjectType)`

### Exception Hierarchy

```java
EntitlementsException (extends RuntimeException)
├── ConfigurationMissingException   // Required config field absent
├── ConfigurationInvalidException   // Invalid config field value
└── EntitlementsQueryException      // SpiceDB gRPC error (carries Status.Code)
    └── EntitlementsTimeoutException // DEADLINE_EXCEEDED specifically
```

### SpiceDB gRPC RPCs Used

| RPC                    | Used By                    | Semantics                           |
|------------------------|----------------------------|-------------------------------------|
| `CheckPermission`      | `FgaSpiceDBQuery`          | Single entity-to-entity check       |
| `CheckBulkPermissions` | `Feature/Permission/Route` | Batch check multiple tuples         |
| `LookupResources`      | `LookupSpiceDBQuery`       | Find all resource IDs (streaming)   |
| `LookupSubjects`       | `LookupSpiceDBQuery`       | Find all subject IDs (streaming)    |

---

## Configuration

### Direct API Usage

```java
ClientConfiguration config = ClientConfiguration.builder()
    .engineEndpoint("grpc.authz.example.com:443")   // Required
    .engineToken("your-spicedb-token")                // Required
    .requestTimeout(Duration.ofSeconds(5))            // Default: 5s
    .bulkRequestTimeout(Duration.ofSeconds(15))       // Default: 15s
    .maxRetries(3)                                    // Default: 3
    .useTls(true)                                     // Default: true
    .monitoring(false)                                // Default: false
    .cacheConfiguration(CacheConfiguration.defaults()) // Optional
    .fallbackStrategy(new StaticFallback(false))      // Optional
    .build();

EntitlementsClient client = EntitlementsClientFactory.create(config);
```

### Spring Boot Properties

```yaml
frontegg:
  entitlements:
    enabled: true                    # default: true
    engine-endpoint: grpc.authz.example.com:443
    engine-token: ${ENTITLEMENTS_ENGINE_TOKEN}
    use-tls: true                    # default: true
    request-timeout: 5s              # default: 5s
    bulk-request-timeout: 15s        # default: 15s
    max-retries: 3                   # default: 3
    monitoring: false                # default: false
    fallback-result: false           # null = no fallback (static only)
    cache:
      max-size: 10000               # default: 10000
      expire-after-write: 60s       # default: 60s
```

### Configuration Defaults and Constraints

| Field              | Default | Validation                                 |
|--------------------|---------|---------------------------------------------|
| engineEndpoint     | —       | Required, non-blank                         |
| engineToken        | —       | Required, non-blank                         |
| requestTimeout     | 5s      | Must be positive                            |
| bulkRequestTimeout | 15s     | Must be positive                            |
| maxRetries         | 3       | Must be >= 0                                |
| useTls             | true    | —                                           |
| monitoring         | false   | —                                           |
| cacheConfiguration | null    | If provided, maxSize > 0, expiry > 0        |
| fallbackStrategy   | null    | If provided, must be StaticFallback or FunctionFallback |

---

## Integration Points and External Dependencies

### External: SpiceDB Authorization Engine

| Aspect            | Detail                                                |
|-------------------|-------------------------------------------------------|
| Protocol          | gRPC over HTTP/2                                      |
| Authentication    | Bearer token via `BearerTokenCallCredentials`         |
| Token rotation    | Supported via `Supplier<String>` (re-evaluated per call) |
| TLS               | Enabled by default (port 443), plaintext optional (port 50051) |
| Channel settings  | Keep-alive 30s, max inbound 16MB                     |

### Internal: Caffeine Cache

- Optional dependency — must be explicitly added by consumer
- `CacheProvider<K,V>` interface allows alternative implementations
- Currently only `CaffeineCacheProvider` shipped

### Internal: SLF4J Logging

- Facade only — no binding shipped
- Consumer must provide a binding (logback, log4j2, etc.)
- Logging levels used: DEBUG (cache hits), INFO (monitoring results), WARN (retry attempts), ERROR (query failures)

### Spring Boot Integration

- `@ConditionalOnProperty(prefix="frontegg.entitlements", name="enabled", havingValue="true", matchIfMissing=true)`
- Creates `EntitlementsClient` bean
- Supports `@ConfigurationProperties` binding
- Compatible with Spring Boot 3.2+

---

## Development and Deployment

### Local Development Setup

```bash
# Prerequisites: Java 17+, Maven 3.x
# Clone and build
git clone https://github.com/frontegg/entitlements-client-java.git
cd entitlements-client-java
mvn clean install            # Build + unit tests

# Run integration tests (requires Docker)
mvn verify -P integration    # Starts SpiceDB via Testcontainers
```

### Build Commands

```bash
mvn clean install                   # Build all modules + run unit tests
mvn verify -P integration           # Run integration tests (Docker required)
mvn deploy -P release -DskipTests   # Publish to Maven Central (CI only)
```

### CI/CD Pipeline

**ci.yaml** (on push to master or PR):
- Matrix build: Java 17 + Java 21
- Runs `mvn verify` (unit tests)
- Runs `mvn verify -P integration` (SpiceDB Testcontainers)
- Compiles all submodules

**publish.yaml** (on tag `v*`):
1. Verifies GPG key (checks for missing/expired)
2. Sets version from git tag (`v0.1.0-beta.1` → `0.1.0-beta.1`)
3. Signs artifacts with GPG
4. Publishes all 4 modules to Maven Central via `central-publishing-maven-plugin`

### Release Process

- Semantic-release via `.releaserc.yaml`
- Branches: `master` (release), `next` (alpha prerelease)
- Conventional commits (`feat:`, `fix:`, `BREAKING CHANGE:`)
- Tag format: `v{version}` (e.g., `v0.1.0-beta.1`)

---

## Testing Reality

### Unit Tests (20 files)

| Area               | Files                                           | What's Tested                        |
|--------------------|-------------------------------------------------|--------------------------------------|
| Configuration      | `ClientConfigurationTest`, `InternalClientFactoryTest` | Builder validation, defaults, endpoint parsing |
| Models             | `ModelRecordValidationTest`, `LookupModelsTest` | Sealed interface exhaustiveness, immutability |
| Strategies         | `Feature/Permission/Fga/Route/LookupSpiceDBQueryTest` | gRPC request construction, response mapping |
| Client             | `SpiceDBEntitlementsClientTest`                 | Retry, fallback, caching, monitoring, async |
| Retry              | `RetryHandlerTest`                              | Backoff calculation, retryable codes |
| Cache              | `CaffeineCacheProviderTest`                     | Hit/miss, TTL, max size, concurrent access |
| Utilities          | `Base64UtilsTest`, `BearerTokenCallCredentialsTest`, `CaveatContextBuilderTest` | Encoding, auth, Struct conversion |

### Integration Tests

- **Location**: `src/test/java/.../integration/SpiceDBIntegrationTest.java`
- **Infrastructure**: Testcontainers + `SpiceDBContainer` (custom Docker container helper)
- **Schema**: `SpiceDBSchemaWriter` initializes test schemas
- **Profile**: `-P integration` (Maven failsafe plugin)
- **Requirements**: Docker daemon running

### Test Utilities for Consumers

- `MockEntitlementsClient` — default all-denied, configurable per-check, records calls
- `RecordingEntitlementsClient` — wraps real client, records interactions

---

## Technical Debt and Known Issues

### Current Status: Minimal Debt (Beta)

This is a v0.1.0-beta.1 codebase built from scratch. No legacy code or accumulated debt.

### Noted Patterns and Constraints

1. **`BearerTokenCallCredentials` uses deprecated gRPC API**: The `applyRequestMetadata` method overrides a deprecated gRPC `CallCredentials` API. This works but will need updating when gRPC removes it.

2. **`tokenAuth` removed from pom.xml**: The `central-publishing-maven-plugin` 0.10.0 no longer supports the `tokenAuth` configuration parameter. It was removed during the v0.1.0-beta.1 publish debugging.

3. **Multiple test constructors on `SpiceDBEntitlementsClient`**: Has 4 constructors — 1 production, 3 for test injection. This is intentional for testability but adds surface area.

4. **Caveat context silently drops unsupported types**: `CaveatContextBuilder` ignores attribute values that aren't String/Number/Boolean/null without warning. This is by design but could surprise consumers.

5. **Spring Boot Starter only supports `StaticFallback`**: The properties-based configuration (`fallback-result: true/false`) can only create a `StaticFallback`. `FunctionFallback` requires programmatic bean definition.

6. **No Route pattern pre-compilation**: `RouteSpiceDBQuery` sends route patterns as-is to SpiceDB. Route regex compilation happens server-side, not in the client.

### Constraints to Respect

- **Java 17 minimum** — Sealed interfaces and records are core to the API; cannot drop to 11/8
- **Caffeine is optional** — Don't make it a required dependency; the `CacheProvider` interface exists for alternatives
- **Base64 encoding must be URL-safe without padding** — Cross-SDK compatibility with TypeScript SDK
- **Package-private `internal` package** — Don't expose internal classes in public API
- **Immutable models** — All public model types are records or sealed interfaces; don't add mutability

---

## Appendix — Useful Commands and Scripts

### Frequently Used Commands

```bash
mvn clean install                      # Full build + unit tests
mvn verify -P integration              # Integration tests (Docker required)
mvn versions:set -DnewVersion=X.Y.Z   # Set version across modules
mvn dependency:tree                    # Inspect dependency graph
```

### Debugging and Troubleshooting

- **gRPC debug logging**: Set `io.grpc` logger to DEBUG in your SLF4J config
- **Cache behavior**: Set `com.frontegg.sdk.entitlements` logger to DEBUG for cache hit/miss logs
- **SpiceDB connectivity**: Test endpoint with `grpcurl` before troubleshooting the SDK
- **Integration test failures**: Ensure Docker daemon is running and ports 50051/50052 are free
