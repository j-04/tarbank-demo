# tarbank-demo

Tarbank is a Java 25, Spring Boot banking-service demo. The bootstrap phase provides the Gradle build, database and Redis runtime configuration, Liquibase baseline, Log4j2 logging, correlation IDs, and internal health probes.

## Local bootstrap configuration

Copy `.env.example` to `.env`, replace every placeholder with a local secret, then start the isolated application stack:

```bash
docker compose up --build
```

The application is available on port 8080. PostgreSQL, Redis, and the management server have no host-published ports. Liveness and readiness probes run only on the app container's loopback management port.

The local runtime requires Java 25 when Gradle is used directly. Docker builds the application with the pinned Gradle 9.8.0/JDK 25 image.