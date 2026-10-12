# External Libraries

Third-party libraries this project depends on. No `deployza-lib-*` internal libraries are
used -- this project is standalone, not a deployza-org repo (see `CLAUDE.md`).

| Library | Version | Why |
|---|---|---|
| Spring Framework (webmvc, orm, jdbc, tx) | 6.2.x | Core MVC + DI + transaction management |
| Spring Security (web, config) | 6.4.x | Auth filter chain, cookie-based session authentication |
| Spring Data JPA | 3.4.x | `JpaRepository` for `*Repo` interfaces |
| Hibernate ORM | 6.6.x | JPA provider |
| Jackson (jackson-databind) | 2.18.x | JSON request/response body conversion |
| Jackson (jackson-datatype-jsr310) | 2.18.x | `java.time` (`LocalDate`) serialization -- `SessionData.dateOfJoining`/`dateOfExit` |
| MySQL Connector/J | 9.1.x | Production database driver |
| H2 | 2.3.x | Test-only in-memory database |
| Flyway (core, mysql) | 11.20.x | Versioned schema migrations, run at startup via `FlywayConfig`. Pinned to 11.x, not the latest 13.x -- 13.x pulls in a Jackson 3 (`tools.jackson`) dependency internally that conflicts with this project's Jackson 2.x stack (missing-class error at runtime); 11.x has no such conflict |
| Lombok | 1.18.x | `@Getter`/`@Setter` on POJOs, Forms, Data classes |
| SLF4J + Logback | 2.0.x / 1.5.x | Logging |
| JUnit Jupiter | 5.11.x | Test framework |
| razorpay-java | 1.4.x (placeholder) | Billing/subscription gateway SDK, gated by `razorpay.enabled` (default false) -- see `RazorpayGatewayService` and `prompts/features/billing-subscription.md`. **Licence NOT YET CONFIRMED in this session** -- must be permissive (Apache 2.0/MIT/BSD/ISC per `security-standards`) before `razorpay.enabled=true` is used anywhere; the version above is a placeholder pending that check. No code currently imports `com.razorpay.*` -- the real SDK calls are also unwired pending the same confirmation (see that class's header comment) |
