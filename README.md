# Frontegg Entitlements Client for Java

Java SDK for Frontegg's entitlements engine. Perform feature, permission, and fine-grained
authorization (FGA) checks from JVM applications against Frontegg's SpiceDB-backed ReBAC engine
over gRPC.

The client is thread-safe, manages a single multiplexed HTTP/2 gRPC channel, and is designed to
be created once and shared for the lifetime of an application.

---

## Table of Contents

- [Requirements](#requirements)
- [Modules](#modules)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage](#usage)
  - [Configuration](#configuration)
  - [Feature Entitlement Check](#feature-entitlement-check)
  - [Permission Entitlement Check](#permission-entitlement-check)
  - [Route Entitlement Check](#route-entitlement-check)
  - [Fine-Grained Authorization (FGA)](#fine-grained-authorization-fga)
  - [Async API](#async-api)
  - [Lookup Operations](#lookup-operations)
  - [Time-Based Access](#time-based-access)
  - [Caching](#caching)
  - [Fallback Strategies](#fallback-strategies)
  - [Monitoring Mode](#monitoring-mode)
  - [Error Handling](#error-handling)
- [Spring Boot Starter](#spring-boot-starter)
  - [Quarkus and Micronaut](#quarkus-and-micronaut)
- [Test Utilities](#test-utilities)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [License](#license)

---

## Requirements

- Java 17 or later (sealed interfaces and records are used throughout the public API)
- Compatible with Spring Boot 3.2+, Quarkus 3.x, and Micronaut 4.x
- An SLF4J-compatible logging implementation on the runtime classpath (e.g. Logback, Log4j2)

---

## Modules

The SDK is published as four separate artifacts. Use the BOM to align versions across them.

| Module               | Artifact ID                              | Description                                                  |
|----------------------|------------------------------------------|--------------------------------------------------------------|
| Core                 | `entitlements-client`                    | Main SDK — gRPC client, check API, fallback, caching         |
| BOM                  | `entitlements-client-bom`                | Bill of Materials for aligning dependency versions           |
| Spring Boot Starter  | `entitlements-client-spring-boot-starter`| Auto-configures `EntitlementsClient` from `application.properties` |
| Test Utilities       | `entitlements-client-test`               | `MockEntitlementsClient` and `RecordingEntitlementsClient`   |

---

## Installation

### Maven

```xml
<dependency>
    <groupId>com.frontegg.sdk</groupId>
    <artifactId>entitlements-client</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.frontegg.sdk:entitlements-client:0.1.0'
```

Or with the Kotlin DSL:

```kotlin
implementation("com.frontegg.sdk:entitlements-client:0.1.0")
```

### BOM (Optional)

If you use the Frontegg BOM to align dependency versions across multiple Frontegg SDK artifacts,
import it in your `dependencyManagement` block and then declare the dependency without a version:

**Maven:**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.frontegg.sdk</groupId>
            <artifactId>entitlements-client-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.frontegg.sdk</groupId>
        <artifactId>entitlements-client</artifactId>
    </dependency>
</dependencies>
```

**Gradle:**

```kotlin
implementation(platform("com.frontegg.sdk:entitlements-client-bom:0.1.0"))
implementation("com.frontegg.sdk:entitlements-client")
```

### Spring Boot users

Spring Boot applications can use the starter module instead of the core artifact. The starter
provides auto-configuration driven entirely by `application.properties` — no Java configuration
class is required. See the [Spring Boot Starter](#spring-boot-starter) section for details.

---

## Quick Start

The following example shows the minimal steps needed to check whether a user is entitled to a
feature:

```java
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;

// 1. Build the configuration. engineEndpoint and engineToken are required.
ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .build();

// 2. Create the client. Validate at startup — the factory throws immediately on
//    misconfiguration rather than waiting for the first query.
try (EntitlementsClient client = EntitlementsClientFactory.create(config)) {

    // 3. Check a feature flag for a specific user in a tenant.
    EntitlementsResult featureResult = client.isEntitledTo(
            new UserSubjectContext("user-123", "tenant-456"),
            new FeatureRequestContext("advanced-reporting")
    );

    if (featureResult.result()) {
        System.out.println("Access granted");
    } else {
        System.out.println("Access denied");
    }

    // 4. Check a permission for the same user.
    EntitlementsResult permissionResult = client.isEntitledTo(
            new UserSubjectContext("user-123", "tenant-456"),
            new PermissionRequestContext("reports:export")
    );

    if (!permissionResult.result()) {
        throw new AccessDeniedException("Insufficient permissions");
    }
}
// 5. The try-with-resources block closes the client, releasing the gRPC channel.
```

> In a long-running application (Spring Boot, Quarkus, Micronaut) declare the client as a
> singleton bean rather than using try-with-resources. Call `client.close()` in the bean
> destroy lifecycle method.

---

## Usage

### Configuration

`ClientConfiguration` is an immutable value object built with a fluent builder. All required
fields are validated in `build()`.

```java
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import com.frontegg.sdk.entitlements.config.ConsistencyPolicy;
import com.frontegg.sdk.entitlements.fallback.StaticFallback;

import java.time.Duration;

ClientConfiguration config = ClientConfiguration.builder()
        // Required: the SpiceDB gRPC endpoint (host:port)
        .engineEndpoint("grpc.authz.example.com:443")

        // Required: bearer token for authenticating gRPC calls.
        // Pass a static String for simplicity, or a Supplier<String> for rotation.
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))

        // Optional: deny all access if the engine is unreachable (fail-closed).
        .fallbackStrategy(new StaticFallback(false))

        // Optional: per-request deadline. Default: 5 seconds.
        .requestTimeout(Duration.ofSeconds(3))

        // Optional: deadline for bulk checks. Default: 15 seconds.
        .bulkRequestTimeout(Duration.ofSeconds(10))

        // Optional: maximum retry attempts for transient gRPC failures. Default: 3.
        .maxRetries(3)

        // Optional: enable TLS on the gRPC channel. Default: true.
        //           Set to false only for local development against a plaintext server.
        .useTls(true)

        // Optional: enable monitoring mode (see Monitoring Mode section). Default: false.
        .monitoring(false)

        // Optional: SpiceDB read consistency. Default: MINIMIZE_LATENCY.
        //           Use FULLY_CONSISTENT when read-after-write consistency is required.
        .consistencyPolicy(ConsistencyPolicy.MINIMIZE_LATENCY)

        // Optional: enable in-memory result caching (see Caching section). Default: disabled.
        .cacheConfiguration(CacheConfiguration.defaults())

        .build();
```

**All configuration options:**

| Option                | Type                           | Default      | Required | Description                                                                                                   |
|-----------------------|--------------------------------|--------------|----------|---------------------------------------------------------------------------------------------------------------|
| `engineEndpoint`      | `String`                       | —            | Yes      | SpiceDB gRPC endpoint in `host:port` form, e.g. `grpc.authz.example.com:443`.                                |
| `engineToken`         | `String` or `Supplier<String>` | —            | Yes      | Bearer token for gRPC authentication. Pass a `Supplier` to enable credential rotation without recreating the client. |
| `fallbackStrategy`    | `FallbackStrategy`             | `null`       | No       | Determines the result when the engine is unreachable after all retries. Exceptions propagate when not set.    |
| `requestTimeout`      | `Duration`                     | 5 seconds    | No       | gRPC deadline for a single entitlement check.                                                                 |
| `bulkRequestTimeout`  | `Duration`                     | 15 seconds   | No       | gRPC deadline for bulk entitlement checks.                                                                    |
| `maxRetries`          | `int`                          | `3`          | No       | Maximum retry attempts with exponential backoff before the fallback is invoked or the exception propagates.   |
| `useTls`              | `boolean`                      | `true`       | No       | Whether to use TLS on the gRPC channel. Disable only for local development.                                   |
| `monitoring`          | `boolean`                      | `false`      | No       | When `true`, checks are evaluated and logged but always return `allowed`. See [Monitoring Mode](#monitoring-mode). |
| `consistencyPolicy`   | `ConsistencyPolicy`            | `MINIMIZE_LATENCY` | No | SpiceDB read consistency: `MINIMIZE_LATENCY` (fastest, allows stale reads) or `FULLY_CONSISTENT` (linearizable). |
| `cacheConfiguration`  | `CacheConfiguration`           | `null`       | No       | When set, results are cached in memory. `null` disables caching. See [Caching](#caching).                    |

**Credential rotation** is supported by providing a `Supplier<String>` for the token. The
supplier is invoked on every gRPC call so that refreshed credentials are picked up without
restarting the application:

```java
// Example: rotate the token from an external secret store
ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(() -> secretStore.getSecret("entitlements-engine-token"))
        .build();
```

---

### Feature Entitlement Check

Use `FeatureRequestContext` to check whether a subject has access to a named feature flag.

```java
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;

UserSubjectContext subject = new UserSubjectContext("user-123", "tenant-456");
FeatureRequestContext request = new FeatureRequestContext("advanced-reporting");

EntitlementsResult result = client.isEntitledTo(subject, request);

if (result.result()) {
    // Render the advanced reporting UI
}
```

**With subject attributes** — attributes are evaluated by caveat conditions in the authorization
engine (for example, checking a user's subscription plan or role):

```java
import java.util.Map;

UserSubjectContext subject = new UserSubjectContext(
        "user-123",
        "tenant-456",
        Map.of("plan", "enterprise", "region", "us-east-1")
);

EntitlementsResult result = client.isEntitledTo(
        subject,
        new FeatureRequestContext("data-export")
);
```

---

### Permission Entitlement Check

Use `PermissionRequestContext` to check a named permission. `PermissionRequestContext` accepts a
single permission key string.

> **Important:** Permission checks require the subject's permission list to be supplied via
> `UserSubjectContext`. The SDK performs a **client-side short-circuit** before reaching SpiceDB:
> if `UserSubjectContext.permissions()` is empty or the requested key does not match any entry in
> the list, the check is immediately denied without a network call. Pass the user's permission keys
> (obtained from the Frontegg token or session) in the `permissions` argument. Entries support
> simple glob wildcards — `*` matches one or more characters, `.` is treated as a literal dot.

```java
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import java.util.List;

// Supply the user's permission keys to enable the check.
// These typically come from the decoded Frontegg access token.
UserSubjectContext subject = new UserSubjectContext(
        "user-123",
        "tenant-456",
        List.of("reports:read", "reports:export", "fe.billing.*")  // user's permission list
);

// Single permission check
EntitlementsResult readResult = client.isEntitledTo(
        subject,
        new PermissionRequestContext("reports:read")
);

if (!readResult.result()) {
    throw new AccessDeniedException("reports:read is required");
}

// Check a second permission for the same subject
EntitlementsResult exportResult = client.isEntitledTo(
        subject,
        new PermissionRequestContext("reports:export")
);

if (!exportResult.result()) {
    throw new AccessDeniedException("reports:export is required");
}
```

The `permissions` list also supports glob wildcards, so a user holding `"fe.billing.*"` will pass
a check for `"fe.billing.read"` at the client-side stage before any SpiceDB call is made.

---

### Route Entitlement Check

Use `RouteRequestContext` to check whether a subject is permitted to access a specific HTTP
method and path. The authorization engine matches the path against `frontegg_route` relationships
registered in the SpiceDB schema, including regular-expression patterns.

```java
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;

UserSubjectContext subject = new UserSubjectContext("user-123", "tenant-456");

EntitlementsResult result = client.isEntitledTo(
        subject,
        new RouteRequestContext("GET", "/api/v1/reports")
);

if (!result.result()) {
    throw new AccessDeniedException("Route access denied");
}
```

`RouteRequestContext` accepts an HTTP method and a path. A two-argument convenience constructor
checks access at the current time. To evaluate access at a specific point in time pass an
`Instant` as the third argument — see [Time-Based Access](#time-based-access).

---

### Fine-Grained Authorization (FGA)

FGA checks operate on arbitrary entity types rather than Frontegg users. Use
`EntitySubjectContext` to identify the principal and `EntityRequestContext` to express the
resource and the relation being checked.

```java
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;

// Subject: a service account identified by type and ID
EntitySubjectContext subject = new EntitySubjectContext("service_account", "svc-deployer-01");

// Request: does the subject have the "deployer" relation on the environment "prod"?
EntityRequestContext request = new EntityRequestContext("environment", "prod", "deployer");

EntitlementsResult result = client.isEntitledTo(subject, request);

if (!result.result()) {
    throw new AccessDeniedException("svc-deployer-01 is not a deployer for prod");
}
```

A more concrete document-sharing example:

```java
// Check whether a user (as an entity) is a viewer of a specific document
EntitySubjectContext subject = new EntitySubjectContext("user", "user-123");
EntityRequestContext request = new EntityRequestContext("document", "doc-789", "viewer");

EntitlementsResult result = client.isEntitledTo(subject, request);
```

---

### Async API

Every check has a non-blocking counterpart that returns a `CompletableFuture`. The future
completes on a gRPC callback thread. Chain blocking operations on a separate executor.

```java
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

Executor appExecutor = Executors.newCachedThreadPool(); // Java 17+
// On Java 21+, prefer: Executors.newVirtualThreadPerTaskExecutor()

CompletableFuture<EntitlementsResult> future = client.isEntitledToAsync(
        new UserSubjectContext("user-123", "tenant-456"),
        new FeatureRequestContext("advanced-reporting")
);

future
    .thenApplyAsync(result -> {
        if (result.result()) {
            return renderAdvancedReport();
        }
        return renderBasicReport();
    }, appExecutor)
    .exceptionally(throwable -> {
        // Completed exceptionally when no fallback is configured and the engine fails
        logger.error("Entitlement check failed", throwable);
        return renderBasicReport();
    });
```

For Spring WebFlux or other reactive pipelines, wrap the `CompletableFuture` with
`Mono.fromFuture(...)` or `Flux.from(...)` as needed.

Async variants exist for lookup operations as well: `lookupResourcesAsync` and
`lookupSubjectsAsync` both return `CompletableFuture<LookupResult>`.

---

### Lookup Operations

Lookup operations answer the inverse of a point-in-time entitlement check: instead of asking
"does subject X have access to resource Y?", they ask "which resources does subject X have access
to?" or "which subjects have access to resource Y?". Both operations map to SpiceDB's
`LookupResources` and `LookupSubjects` RPCs respectively.

#### lookupResources

Find all resources of a given type that a subject has access to.

```java
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;

// Find all frontegg_feature resources on which "user-123" holds the "entitled" permission
LookupResourcesRequest request = new LookupResourcesRequest(
        "frontegg_user",   // subjectType
        "user-123",        // subjectId
        "entitled",        // permission
        "frontegg_feature" // resourceType
);

LookupResult result = client.lookupResources(request);

// result.entityIds() is an immutable List<String> of matching resource IDs
for (String featureId : result.entityIds()) {
    System.out.println("User has access to feature: " + featureId);
}
```

#### lookupSubjects

Find all subjects of a given type that have access to a resource.

```java
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;

// Find all frontegg_user subjects that hold the "viewer" permission on document "doc-789"
LookupSubjectsRequest request = new LookupSubjectsRequest(
        "document",      // resourceType
        "doc-789",       // resourceId
        "viewer",        // permission
        "frontegg_user"  // subjectType
);

LookupResult result = client.lookupSubjects(request);

for (String userId : result.entityIds()) {
    System.out.println("User can view document: " + userId);
}
```

**Result type:**

`LookupResult` is a record with a single field:

| Field        | Type           | Description                                                              |
|--------------|----------------|--------------------------------------------------------------------------|
| `entityIds`  | `List<String>` | Immutable list of matching entity IDs. May be empty; never `null`.       |

The order of IDs returned is not guaranteed to be stable across calls.

---

### Time-Based Access

The `"at"` parameter for time-based access is forwarded to the authorization engine as the
`"at"` field in the caveat context (ISO-8601 format), enabling entitlement checks at a specific
point in time (past or future). When `at` is omitted, the check is evaluated at the current time.

This is useful for previewing scheduled access windows, auditing historical decisions, or
implementing time-limited entitlements.

`FeatureRequestContext` and `RouteRequestContext` support an optional `Instant at` argument.
`PermissionRequestContext` does not have a time-based variant — use `FeatureRequestContext` or
`RouteRequestContext` for time-gated access control.

```java
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;

import java.time.Instant;

UserSubjectContext subject = new UserSubjectContext("user-123", "tenant-456");

// Feature check at a fixed point in time
Instant trialExpiry = Instant.parse("2026-06-30T23:59:59Z");
EntitlementsResult featureResult = client.isEntitledTo(
        subject,
        new FeatureRequestContext("advanced-reporting", trialExpiry)
);

// Route check at a fixed point in time
EntitlementsResult routeResult = client.isEntitledTo(
        subject,
        new RouteRequestContext("GET", "/api/v1/reports", trialExpiry)
);
```

The single-argument constructors are equivalent to passing `null` for `at`:

```java
// These two are equivalent
new FeatureRequestContext("advanced-reporting")
new FeatureRequestContext("advanced-reporting", null)
```

---

### Caching

The SDK includes optional in-memory result caching backed by
[Caffeine](https://github.com/ben-manes/caffeine). When enabled, each successful entitlement
result is stored keyed by the `(subject, request)` pair and returned directly on subsequent
identical calls until the entry expires. Caching is disabled by default.

#### Enabling caching

Pass a `CacheConfiguration` to the builder:

```java
import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;

import java.time.Duration;

// Use built-in defaults: 10,000 entries, 60-second write TTL
ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .cacheConfiguration(CacheConfiguration.defaults())
        .build();
```

To tune the cache size or TTL, construct a `CacheConfiguration` directly:

```java
// Custom configuration: 5,000 entries, 30-second write TTL
CacheConfiguration cacheConfig = new CacheConfiguration(5_000, Duration.ofSeconds(30));

ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .cacheConfiguration(cacheConfig)
        .build();
```

**Cache defaults:**

| Parameter          | Default value   | Description                                                    |
|--------------------|-----------------|----------------------------------------------------------------|
| `maxSize`          | `10,000`        | Maximum number of cached entries (LRU eviction when exceeded)  |
| `expireAfterWrite` | `60 seconds`    | Time-to-live measured from the moment the entry was written    |

#### Caffeine dependency

The Caffeine library is declared as an `optional` dependency in the core SDK artifact. If you
enable caching by setting a `CacheConfiguration`, you must add the Caffeine dependency explicitly
to your project:

**Maven:**

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.2.3</version>
</dependency>
```

**Gradle:**

```groovy
implementation 'com.github.ben-manes.caffeine:caffeine:3.2.3'
```

If Caffeine is not on the classpath and caching is configured, the client throws a
`NoClassDefFoundError` when `EntitlementsClientFactory.create(...)` is called.

> Cached results are never returned when monitoring mode is active and are not stored for
> fallback results — only real engine responses are cached.

---

### Fallback Strategies

A fallback strategy is invoked after all retry attempts are exhausted due to an engine error or
timeout. Without a fallback, exceptions propagate to the caller.

There are two built-in strategies.

#### Static Fallback

`StaticFallback` always returns the same fixed result regardless of which request failed or what
error occurred. It is the simplest option and is appropriate when a uniform fail-open or
fail-closed policy is acceptable for the whole application.

```java
import com.frontegg.sdk.entitlements.fallback.StaticFallback;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;

// Fail-closed: deny all requests when the engine is unavailable (recommended for security)
ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .fallbackStrategy(new StaticFallback(false))
        .build();
```

```java
// Fail-open: allow all requests when the engine is unavailable
// Use with caution — appropriate only when availability is more important than security
ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .fallbackStrategy(new StaticFallback(true))
        .build();
```

#### Custom Function Fallback

`FunctionFallback` delegates the decision to a `Function<FallbackContext, EntitlementsResult>`.
The `FallbackContext` exposes the original `SubjectContext`, `RequestContext`, and the root-cause
`Throwable`, enabling context-sensitive logic.

```java
import com.frontegg.sdk.entitlements.fallback.FunctionFallback;
import com.frontegg.sdk.entitlements.fallback.FallbackContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.exception.EntitlementsTimeoutException;

FunctionFallback fallback = new FunctionFallback(ctx -> {
    // Log every fallback invocation for observability
    logger.warn("Entitlement check failed for subject={}, request={}, error={}",
            ctx.subjectContext(),
            ctx.requestContext(),
            ctx.error().getMessage());

    // Allow access to a specific low-risk feature even when the engine is down
    if (ctx.requestContext() instanceof FeatureRequestContext f
            && "status-page".equals(f.featureKey())) {
        return EntitlementsResult.allowed();
    }

    // On timeout, fail-open for internal service accounts
    if (ctx.error() instanceof EntitlementsTimeoutException
            && ctx.subjectContext() instanceof EntitySubjectContext e
            && "service_account".equals(e.entityType())) {
        return EntitlementsResult.allowed();
    }

    // Default: fail-closed
    return EntitlementsResult.denied();
});

ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .fallbackStrategy(fallback)
        .build();
```

---

### Monitoring Mode

Monitoring mode is a deployment safety mechanism that lets teams observe what the authorization
engine would decide — without enforcing those decisions. When monitoring mode is enabled:

1. The SDK sends the real check to SpiceDB and logs the result.
2. The method always returns `EntitlementsResult(result=true, monitoring=true)` to the caller
   regardless of what the engine returned.
3. The `monitoring` flag on the result is set to `true`, allowing callers that inspect the flag
   to distinguish monitoring results from enforcement results.

This is useful when introducing entitlement checks into existing code paths where denying access
prematurely would break users. Enable enforcement once the observed results confirm the
authorization data is correct.

```java
ClientConfiguration config = ClientConfiguration.builder()
        .engineEndpoint("grpc.authz.example.com:443")
        .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
        .monitoring(true)  // observe-only, never deny
        .build();

EntitlementsClient client = EntitlementsClientFactory.create(config);

EntitlementsResult result = client.isEntitledTo(
        new UserSubjectContext("user-123", "tenant-456"),
        new FeatureRequestContext("advanced-reporting")
);

// result.result()     → always true in monitoring mode
// result.monitoring() → true, indicating this was a monitoring check

if (result.monitoring()) {
    // Safe to log but do not enforce
    logger.info("Monitoring check completed, engine said: {}",
            result.result() ? "allowed" : "denied");
}
```

Switch to enforcement by setting `monitoring(false)` or removing the call — `false` is the
default.

> When monitoring mode is active, the in-memory cache (if configured) is bypassed entirely —
> cached results are never returned and monitoring results are never stored. Every call goes
> directly to SpiceDB.

---

### Error Handling

All SDK exceptions extend `EntitlementsException` (an unchecked `RuntimeException`). The full
hierarchy is:

```
EntitlementsException  (RuntimeException)
├── ConfigurationMissingException   — a required configuration field (endpoint or token) is absent
├── ConfigurationInvalidException   — a configuration field has an invalid value
└── EntitlementsQueryException      — the authorization engine returned an error
    └── EntitlementsTimeoutException — the gRPC deadline was exceeded
```

`ConfigurationMissingException` and `ConfigurationInvalidException` are thrown at client
creation time by `EntitlementsClientFactory.create(...)`. They indicate a programming error and
are not expected in production.

`EntitlementsQueryException` and `EntitlementsTimeoutException` are thrown (or used to
exceptionally complete the `CompletableFuture`) at query time when no fallback strategy is
configured.

```java
import com.frontegg.sdk.entitlements.exception.EntitlementsTimeoutException;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.exception.ConfigurationMissingException;
import com.frontegg.sdk.entitlements.exception.ConfigurationInvalidException;

// At startup — catch configuration exceptions once during bean initialization
try {
    client = EntitlementsClientFactory.create(config);
} catch (ConfigurationMissingException e) {
    throw new IllegalStateException("Missing required entitlements configuration: " + e.getMessage(), e);
} catch (ConfigurationInvalidException e) {
    throw new IllegalStateException("Invalid entitlements configuration: " + e.getMessage(), e);
}

// At query time — catch query exceptions per request when no fallback is configured
try {
    EntitlementsResult result = client.isEntitledTo(subject, request);
    return result.result();
} catch (EntitlementsTimeoutException e) {
    // The gRPC deadline was exceeded — consider a conservative default
    logger.warn("Entitlement check timed out, defaulting to denied", e);
    return false;
} catch (EntitlementsQueryException e) {
    // The engine returned an error — inspect e.getCause() for the gRPC status
    logger.error("Entitlement check failed", e);
    return false;
}
```

When a fallback strategy is configured, `EntitlementsQueryException` and
`EntitlementsTimeoutException` are swallowed by the SDK and the fallback result is returned
instead — no try-catch is needed at query sites.

**Which gRPC errors are retried:** only `UNAVAILABLE` and `DEADLINE_EXCEEDED` status codes
trigger the retry+backoff logic. Non-transient errors (`PERMISSION_DENIED`, `UNAUTHENTICATED`,
`INVALID_ARGUMENT`, `NOT_FOUND`, etc.) are thrown immediately on the first attempt without
sleeping or retrying.

---

## Spring Boot Starter

The `entitlements-client-spring-boot-starter` module auto-configures an `EntitlementsClient`
bean from `application.properties` or `application.yml`. No Java configuration class is required.

### Dependency

**Maven:**

```xml
<dependency>
    <groupId>com.frontegg.sdk</groupId>
    <artifactId>entitlements-client-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Gradle:**

```groovy
implementation 'com.frontegg.sdk:entitlements-client-spring-boot-starter:0.1.0'
```

Or with the Kotlin DSL:

```kotlin
implementation("com.frontegg.sdk:entitlements-client-spring-boot-starter:0.1.0")
```

### Configuration properties

Add the following to `application.properties`:

```properties
# Required
frontegg.entitlements.engine-endpoint=grpc.authz.example.com:443
frontegg.entitlements.engine-token=${ENTITLEMENTS_ENGINE_TOKEN}

# Optional — all values shown are the defaults
frontegg.entitlements.use-tls=true
frontegg.entitlements.request-timeout=5s
frontegg.entitlements.bulk-request-timeout=15s
frontegg.entitlements.max-retries=3
frontegg.entitlements.monitoring=false
frontegg.entitlements.consistency-policy=minimize_latency

# Optional — static fallback result when the engine is unreachable.
# true = fail-open, false = fail-closed. Omit to propagate exceptions instead.
# frontegg.entitlements.fallback-result=false

# Optional — set to false to disable auto-configuration entirely
frontegg.entitlements.enabled=true

# Optional — enable in-memory caching (requires Caffeine on the classpath)
frontegg.entitlements.cache.max-size=10000
frontegg.entitlements.cache.expire-after-write=60s
```

Or equivalently in `application.yml`:

```yaml
frontegg:
  entitlements:
    engine-endpoint: grpc.authz.example.com:443
    engine-token: ${ENTITLEMENTS_ENGINE_TOKEN}
    use-tls: true
    request-timeout: 5s
    bulk-request-timeout: 15s
    max-retries: 3
    monitoring: false
    consistency-policy: minimize_latency
    fallback-result: false
    cache:
      max-size: 10000
      expire-after-write: 60s
```

### Auto-configuration conditions

The `EntitlementsClient` bean is created automatically when all of the following conditions are
satisfied:

1. `entitlements-client` is on the classpath (`@ConditionalOnClass`).
2. `frontegg.entitlements.enabled` is `true` (default).
3. No existing `EntitlementsClient` bean is present in the application context.

Both `frontegg.entitlements.engine-endpoint` and `frontegg.entitlements.engine-token` must also
be set to non-blank values — the auto-configuration throws `IllegalStateException` at bean
creation time if either is missing.

The third condition means you can override the auto-configured bean entirely by declaring your
own `@Bean` method that returns an `EntitlementsClient`. This is the recommended approach when
you need credential rotation via a `Supplier<String>` for the token, which is not expressible in
properties files:

```java
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.fallback.FunctionFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntitlementsConfig {

    @Bean
    public EntitlementsClient entitlementsClient(SecretStore secretStore) {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint("grpc.authz.example.com:443")
                // Supplier enables token rotation without recreating the client
                .engineToken(() -> secretStore.getSecret("entitlements-engine-token"))
                // Custom fallback logic (not expressible in application.properties)
                .fallbackStrategy(new FunctionFallback(ctx -> {
                    logger.warn("Entitlement engine unreachable: {}", ctx.error().getMessage());
                    return EntitlementsResult.denied();
                }))
                .build();
        return EntitlementsClientFactory.create(config);
    }
}
```

### Injecting the client

Once the starter is on the classpath and properties are configured, inject the client as a normal
Spring bean:

```java
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final EntitlementsClient entitlementsClient;

    public ReportService(EntitlementsClient entitlementsClient) {
        this.entitlementsClient = entitlementsClient;
    }

    public boolean canAccessAdvancedReporting(String userId, String tenantId) {
        EntitlementsResult result = entitlementsClient.isEntitledTo(
                new UserSubjectContext(userId, tenantId),
                new FeatureRequestContext("advanced-reporting")
        );
        return result.result();
    }
}
```

### Quarkus and Micronaut

For Quarkus and Micronaut applications, there is no framework-specific starter module. Create the
client manually as a managed singleton bean and call `close()` in the destroy lifecycle:

**Quarkus (CDI):**

```java
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import io.quarkus.runtime.Shutdown;

@ApplicationScoped
public class EntitlementsProducer {

    private EntitlementsClient client;

    @Produces
    @Singleton
    public EntitlementsClient entitlementsClient() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint(System.getenv("ENTITLEMENTS_ENGINE_ENDPOINT"))
                .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
                .build();
        client = EntitlementsClientFactory.create(config);
        return client;
    }

    @Shutdown
    public void onShutdown() {
        if (client != null) client.close();
    }
}
```

**Micronaut:**

```java
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.event.ApplicationShutdownEvent;
import jakarta.inject.Singleton;

@Factory
public class EntitlementsFactory {

    @Singleton
    public EntitlementsClient entitlementsClient() {
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint(System.getenv("ENTITLEMENTS_ENGINE_ENDPOINT"))
                .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
                .build();
        return EntitlementsClientFactory.create(config);
    }
}
```

Register a shutdown listener or use `@PreDestroy` on a wrapper bean to call `client.close()`.

---

## Test Utilities

The `entitlements-client-test` module provides two implementations of `EntitlementsClient`
designed for use in unit and integration tests. Neither implementation makes any network calls.

### Dependency

Add the dependency in test scope:

**Maven:**

```xml
<dependency>
    <groupId>com.frontegg.sdk</groupId>
    <artifactId>entitlements-client-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

**Gradle:**

```groovy
testImplementation 'com.frontegg.sdk:entitlements-client-test:0.1.0'
```

Or with the Kotlin DSL:

```kotlin
testImplementation("com.frontegg.sdk:entitlements-client-test:0.1.0")
```

### MockEntitlementsClient

`MockEntitlementsClient` is a configurable stub. All entitlement checks return
`EntitlementsResult.denied()` by default. Use `setDefaultResult` to change the default for all
checks, or `setHandler` for per-request logic.

```java
import com.frontegg.sdk.entitlements.test.MockEntitlementsClient;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;

import java.util.List;

// Default: all checks return denied
MockEntitlementsClient mock = new MockEntitlementsClient();

// Allow all checks
mock.setDefaultResult(EntitlementsResult.allowed());

// Custom handler for per-request control
mock.setHandler((subject, request) -> {
    if (request instanceof FeatureRequestContext f
            && "premium-feature".equals(f.featureKey())) {
        return EntitlementsResult.denied();
    }
    return EntitlementsResult.allowed();
});

// Configure lookup results
mock.setDefaultLookupResourcesResult(
        new LookupResult(List.of("feature-a", "feature-b"))
);
mock.setDefaultLookupSubjectsResult(
        new LookupResult(List.of("user-1", "user-2"))
);

// Use in tests
EntitlementsResult result = mock.isEntitledTo(
        new UserSubjectContext("user-123", "tenant-456"),
        new FeatureRequestContext("advanced-reporting")
);

LookupResult resources = mock.lookupResources(
        new LookupResourcesRequest("frontegg_user", "user-123", "entitled", "frontegg_feature")
);
```

`MockEntitlementsClient` also tracks whether `close()` has been called:

```java
mock.close();
assertTrue(mock.isClosed());
```

### RecordingEntitlementsClient

`RecordingEntitlementsClient` is a decorator that wraps a real or mock client and records every
call made to it. Use it to assert that your application code makes the entitlement checks you
expect.

```java
import com.frontegg.sdk.entitlements.test.MockEntitlementsClient;
import com.frontegg.sdk.entitlements.test.RecordingEntitlementsClient;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;

// Wrap any EntitlementsClient implementation with the recorder
MockEntitlementsClient mock = new MockEntitlementsClient();
mock.setDefaultResult(EntitlementsResult.allowed());

RecordingEntitlementsClient recorder = new RecordingEntitlementsClient(mock);

// Run the code under test, passing recorder wherever EntitlementsClient is needed
myService.handleRequest(recorder);

// Assert on recorded isEntitledTo calls
List<RecordingEntitlementsClient.EntitlementCall> calls = recorder.getIsEntitledToCalls();
assertEquals(1, calls.size());

RecordingEntitlementsClient.EntitlementCall call = calls.get(0);
assertEquals("user-123", ((UserSubjectContext) call.subject()).userId());
assertEquals("advanced-reporting",
        ((FeatureRequestContext) call.request()).featureKey());

// Assert on recorded lookup calls
List<LookupResourcesRequest> resourceCalls = recorder.getLookupResourcesCalls();
List<LookupSubjectsRequest> subjectCalls  = recorder.getLookupSubjectsCalls();
```

Call `reset()` to clear all recorded calls between test cases when reusing the same recorder:

```java
recorder.reset();
assertTrue(recorder.getIsEntitledToCalls().isEmpty());
```

**Recorded call types:**

| Method                    | Recorded in                  | Element type                                   |
|---------------------------|------------------------------|------------------------------------------------|
| `isEntitledTo`            | `getIsEntitledToCalls()`     | `EntitlementCall(subject, request)`            |
| `isEntitledToAsync`       | `getIsEntitledToCalls()`     | `EntitlementCall(subject, request)`            |
| `lookupResources`         | `getLookupResourcesCalls()`  | `LookupResourcesRequest`                       |
| `lookupResourcesAsync`    | `getLookupResourcesCalls()`  | `LookupResourcesRequest`                       |
| `lookupSubjects`          | `getLookupSubjectsCalls()`   | `LookupSubjectsRequest`                        |
| `lookupSubjectsAsync`     | `getLookupSubjectsCalls()`   | `LookupSubjectsRequest`                        |

All lists are unmodifiable views backed by thread-safe `CopyOnWriteArrayList` instances, so the
recorder is safe to use in multi-threaded test scenarios.

---

## Compatibility

| Component       | Supported Versions        |
|-----------------|---------------------------|
| Java            | 17, 21                    |
| Spring Boot     | 3.2, 3.3, 3.4             |
| Quarkus         | 3.x                       |
| Micronaut       | 4.x                       |
| gRPC            | 1.78.x (shaded Netty)     |
| SLF4J           | 2.x                       |

The library uses Java 17 language features (sealed interfaces, records, pattern matching) and
cannot be used on Java 11 or earlier.

---

## Contributing

1. Fork the repository and create a feature branch from `master`.
2. Write or update unit tests for all changed behaviour.
3. Ensure `mvn verify` passes locally before opening a pull request.
4. Follow the existing commit message format: `FR-<JIRA-KEY> - <description>`.
5. Open a pull request against `master` with a clear description of the change and its motivation.

### Running tests

```bash
# Unit tests only (no external dependencies)
mvn test

# Integration tests — requires Docker (uses Testcontainers to spin up a real SpiceDB instance)
mvn verify -Pintegration

# End-to-end tests — requires a running SpiceDB instance
mvn verify -Pe2e -Dspicedb.endpoint=<host:port> -Dspicedb.token=<token>
```

For bug reports and feature requests, open a GitHub Issue with a minimal reproducer or a
description of the desired behaviour.

---

## License

This SDK is released under the [MIT License](https://opensource.org/licenses/MIT).

Copyright (c) Frontegg Ltd.
