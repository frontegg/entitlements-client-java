# Coding Standards

## Core Standards

- **Language & Runtime:** Java 17+
- **Build:** Maven 3.9+
- **Test Organization:** `src/test/java` mirrors `src/main/java`. Test classes named `*Test.java`.

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Packages | lowercase, no underscores | `com.frontegg.sdk.entitlements.internal` |
| Classes/Interfaces | PascalCase | `SpiceDBEntitlementsClient` |
| Records | PascalCase | `EntitlementsResult` |
| Methods | camelCase | `isEntitledTo` |
| Constants | UPPER_SNAKE_CASE | `DEFAULT_REQUEST_TIMEOUT` |
| Builder methods | camelCase, no `set` prefix | `.engineEndpoint("...")` |

## Critical Rules

- **No protobuf/gRPC types in public API:** All authzed-java and gRPC types stay in `internal` package. Public API uses only SDK-defined types.
- **All public types must be immutable:** Records by default, classes must have only `final` fields with no setters.
- **Thread safety by design:** No mutable shared state. Strategies must be stateless. Configuration is frozen after build.
- **Base64 encoding must match TypeScript SDK:** Use `Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))` — never any other encoding.
- **Never log tokens or secrets:** The `engineToken` must be redacted in all log output and exception messages. This applies at ALL log levels including DEBUG and TRACE.
- **Use SLF4J, never System.out/err:** All logging via `LoggerFactory.getLogger(ClassName.class)`.

## Java-Specific Guidelines

- **Use records for DTOs:** All value types crossing API boundaries must be records.
- **Use sealed interfaces for union types:** `SubjectContext`, `RequestContext`, `FallbackStrategy` must be sealed with `permits` clause.
- **Use `Optional` sparingly:** Only for truly optional return values. Never as method parameters.
- **Prefer `Supplier<String>` for tokens:** Enables credential rotation without client reinstantiation.
- **Unchecked exceptions only:** No checked exceptions in the public API.

## Error Handling

- **Exception Hierarchy:**
  ```
  EntitlementsException (RuntimeException)
  ├── ConfigurationMissingException
  ├── ConfigurationInvalidException
  └── EntitlementsQueryException
      └── EntitlementsTimeoutException
  ```
- gRPC `StatusRuntimeException` is caught at the adapter boundary and wrapped in SDK exceptions.
- Actionable error messages with fix instructions.

## Logging Standards

- **Library:** SLF4J 2.0.x
- **Levels:**
  - `ERROR`: Unrecoverable failures
  - `WARN`: Fallback activated, retry exhausted, forced shutdown
  - `INFO`: Monitoring mode results, client creation/close
  - `DEBUG`: Check inputs/outputs, cache hits/misses
  - `TRACE`: Raw gRPC request/response (token redacted)
