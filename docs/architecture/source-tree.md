# Source Tree

```
entitlements-client-java/
├── pom.xml                                          # Parent POM (multi-module)
├── .gitignore
├── .releaserc.yaml
├── .github/
│   └── workflows/
│       ├── ci.yaml                                  # Build + test on PRs (Java 17, 21)
│       └── publish.yaml                             # Publish to Maven Central on tags
├── docs/
│   ├── brief.md
│   ├── prd.md
│   ├── architecture.md
│   └── architecture/
│       ├── coding-standards.md
│       ├── tech-stack.md
│       └── source-tree.md
├── entitlements-client-bom/
│   └── pom.xml                                      # Bill of Materials POM
├── entitlements-client-spring-boot-starter/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/frontegg/sdk/entitlements/spring/
│       │   ├── EntitlementsAutoConfiguration.java   # Spring Boot auto-configuration
│       │   └── EntitlementsProperties.java          # @ConfigurationProperties binding
│       └── resources/META-INF/spring/
│           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── entitlements-client-test/
│   ├── pom.xml
│   └── src/main/java/com/frontegg/sdk/entitlements/test/
│       ├── MockEntitlementsClient.java              # Configurable mock for unit tests
│       └── RecordingEntitlementsClient.java         # Records calls for assertion
└── src/
    ├── main/java/com/frontegg/sdk/entitlements/
    │   ├── EntitlementsClient.java                  # Public interface
    │   ├── EntitlementsClientFactory.java            # Factory (public)
    │   ├── model/
    │   │   ├── SubjectContext.java                   # Sealed interface
    │   │   ├── UserSubjectContext.java               # Record
    │   │   ├── EntitySubjectContext.java             # Record
    │   │   ├── RequestContext.java                   # Sealed interface
    │   │   ├── FeatureRequestContext.java            # Record
    │   │   ├── PermissionRequestContext.java         # Record
    │   │   ├── RouteRequestContext.java              # Record
    │   │   ├── EntityRequestContext.java             # Record (FGA)
    │   │   ├── EntitlementsResult.java              # Record
    │   │   ├── LookupResourcesRequest.java          # Record
    │   │   ├── LookupSubjectsRequest.java           # Record
    │   │   └── LookupResult.java                    # Record
    │   ├── config/
    │   │   ├── ClientConfiguration.java             # Builder pattern
    │   │   ├── CacheConfiguration.java              # Record
    │   │   └── ConsistencyPolicy.java               # Enum (MINIMIZE_LATENCY, FULLY_CONSISTENT)
    │   ├── fallback/
    │   │   ├── FallbackStrategy.java                # Sealed interface
    │   │   ├── StaticFallback.java                  # Record
    │   │   ├── FunctionFallback.java                # Record
    │   │   └── FallbackContext.java                  # Record
    │   ├── cache/
    │   │   ├── CacheProvider.java                   # Interface
    │   │   └── CaffeineCacheProvider.java           # Caffeine-backed implementation
    │   ├── exception/
    │   │   ├── EntitlementsException.java            # Base unchecked exception
    │   │   ├── ConfigurationMissingException.java
    │   │   ├── ConfigurationInvalidException.java
    │   │   ├── EntitlementsQueryException.java
    │   │   └── EntitlementsTimeoutException.java
    │   └── internal/                                # Non-public implementation
    │       ├── SpiceDBEntitlementsClient.java        # EntitlementsClient impl
    │       ├── SpiceDBQueryClient.java               # Strategy dispatcher
    │       ├── InternalClientFactory.java            # Internal gRPC channel factory
    │       ├── RetryHandler.java                     # Exponential backoff
    │       ├── Base64Utils.java                      # URL-safe Base64 encoding
    │       ├── BearerTokenCallCredentials.java       # gRPC call credentials
    │       ├── CaveatContextBuilder.java             # Builds protobuf Struct
    │       ├── EntitlementsCacheKey.java             # Cache key record
    │       ├── ConsistencyFactory.java               # Converts ConsistencyPolicy → Supplier<Consistency>
    │       ├── BulkPermissionsExecutor.java          # Executes CheckBulkPermissions RPCs
    │       ├── CheckPermissionExecutor.java          # Executes CheckPermission RPCs
    │       ├── LookupResourcesExecutor.java          # Executes LookupResources RPCs
    │       ├── LookupSubjectsExecutor.java           # Executes LookupSubjects RPCs
    │       ├── FeatureSpiceDBQuery.java
    │       ├── PermissionSpiceDBQuery.java
    │       ├── FgaSpiceDBQuery.java
    │       ├── RouteSpiceDBQuery.java                # Route matching with cached relationships
    │       └── LookupSpiceDBQuery.java               # LookupResources / LookupSubjects dispatch
    └── test/java/com/frontegg/sdk/entitlements/
        ├── EntitlementsClientFactoryTest.java
        ├── e2e/
        │   └── SpiceDBE2ETest.java                  # Full E2E tests (mvn verify -Pe2e)
        ├── integration/
        │   └── SpiceDBIntegrationTest.java          # Integration tests (mvn verify -Pintegration)
        ├── cache/
        │   └── CaffeineCacheProviderTest.java
        ├── config/
        │   └── ClientConfigurationTest.java
        ├── model/
        │   ├── LookupModelsTest.java
        │   └── ModelRecordValidationTest.java
        └── internal/
            ├── SpiceDBEntitlementsClientTest.java
            ├── SpiceDBQueryClientTest.java
            ├── InternalClientFactoryTest.java
            ├── RetryHandlerTest.java
            ├── Base64UtilsTest.java
            ├── BearerTokenCallCredentialsTest.java
            ├── CaveatContextBuilderTest.java
            ├── FeatureSpiceDBQueryTest.java
            ├── PermissionSpiceDBQueryTest.java
            ├── FgaSpiceDBQueryTest.java
            ├── RouteSpiceDBQueryTest.java
            └── LookupSpiceDBQueryTest.java
```
