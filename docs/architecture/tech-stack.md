# Tech Stack

| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| **Language** | Java | 17 | Primary language (LTS, sealed classes + records) |
| **Build** | Maven | 3.9+ | Build and dependency management |
| **SpiceDB Client** | authzed-java | 1.5.4 | gRPC client for SpiceDB |
| **gRPC** | grpc-java | 1.78.0 | gRPC transport (via authzed-java) |
| **Protobuf** | protobuf-java | 4.33.5 | Protocol Buffers (via authzed-java) |
| **Logging** | SLF4J API | 2.0.x | Logging facade |
| **Caching** | Caffeine | 3.2.x | In-memory caching |
| **Spring Integration** | Spring Boot (provided) | 3.2+ | Auto-configuration for the starter module |
| **Testing** | JUnit 5 | 5.10.x | Test framework |
| **Testing** | Mockito | 5.22.x | Mocking library |
| **Publishing** | central-publishing-maven-plugin | 0.10.0 | Maven Central publishing |
| **Signing** | maven-gpg-plugin | 3.2.7 | Artifact signing |
| **CI/CD** | GitHub Actions | N/A | CI and deployment |
| **Versioning** | semantic-release | latest | Automated versioning |

## Dependency Notes

- `authzed-java` v1.5.2+ required (fixes duplicated Google proto classes)
- `grpc-netty-shaded` is included as `runtimeOnly` by authzed-java (relocated Netty, no classpath conflicts)
- BOM module provides `grpc-bom` and `protobuf-bom` for consumer version alignment
- Compatible with Spring Boot 3.2+, Quarkus 3.x, Micronaut 4.x
