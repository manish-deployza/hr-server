# Filter Package Reorg

## Status: Implemented (2026-09-17)

## Overview

Pure structural refactor, no behavior change: move the three servlet filters
(`ApiLoggingFilter`, `JwtAuthFilter`, `RequestIdFilter`) out of `com.leaveapp.security`
into a new `com.leaveapp.filter` package, matching `spring-backend-standards`'
package-layout table (`<root>.filter` for `*Filter` classes; `<root>.security` reserved
for `UserPrincipal`/`UserHelper`/`TenantContext`/`RestAuthEntryPoint`).

Not a feature in the requirements/edge-case/schema sense -- no new endpoint, no DB
change, no config, no library. Filed under `prompts/features/` per the planning
workflow anyway, since it's a structural change worth a record.

**Style reference (read-only):** `product-assess-server`'s `com.deployza.assess.filter`
package (a sibling project) was consulted for filter-implementation style, not package
layout -- that project's `filter` package also holds its context holder, `SecurityConfig`,
and `GlobalExceptionHandler`, which is wider than this org's package-layout table.
leave-backend keeps its existing `filter`/`security`/`exception`/`config` split; the one
convention borrowed is implementation style, applied to all three moved filters:

- `extends OncePerRequestFilter` + `protected void doFilterInternal(HttpServletRequest,
  HttpServletResponse, FilterChain)`, replacing `implements Filter` +
  `doFilter(ServletRequest, ServletResponse, FilterChain)`. Drops the manual
  `(HttpServletRequest) req` / `(HttpServletResponse) res` casts (`ApiLoggingFilter`
  already casts both; `JwtAuthFilter` casts the request). Verified safe: both
  `JwtAuthFilter` (`SecurityConfig.addFilterBefore`) and the reference's
  `AuthenticationFilter`/`ExamAuthenticationFilter` are wired the same way (added
  directly to the Spring Security chain, not via `FilterConfig`/servlet-container
  `init()`), and `ApiLoggingFilter`/`RequestIdFilter`'s `DelegatingFilterProxy` wiring in
  `AppInitializer` is unaffected -- `OncePerRequestFilter.doFilter(ServletRequest,
  ServletResponse, FilterChain)` is still the public entry point the proxy calls.
- **Not** adopting the reference's manual `Cookie[]` iteration for reading the session
  cookie -- `JwtAuthFilter` keeps `CookieUtil.read(...)`, this codebase's existing,
  already-shared utility (ponytail: reuse what's already here over a reference project's
  inline pattern).

## Relevant Files

- `security/ApiLoggingFilter.java` -> `filter/ApiLoggingFilter.java` -- package line;
  `implements Filter` -> `extends OncePerRequestFilter`, `doFilter` ->
  `doFilterInternal` (drops its manual Http casts).
- `security/RequestIdFilter.java` -> `filter/RequestIdFilter.java` -- package line;
  same `OncePerRequestFilter`/`doFilterInternal` conversion (no casts to drop, doesn't
  touch Http-specific request/response features).
- `security/JwtAuthFilter.java` -> `filter/JwtAuthFilter.java` -- package line; same
  `OncePerRequestFilter`/`doFilterInternal` conversion (drops its one manual cast); new
  imports for `com.leaveapp.security.UserPrincipal` and `com.leaveapp.security.TenantContext`
  (same-package references today, cross-package after the move); keeps `CookieUtil.read`.
- `test/java/com/leaveapp/security/JwtAuthFilterTest.java` -> `test/java/com/leaveapp/filter/JwtAuthFilterTest.java`
  -- package line + same new imports as the class under test. No behavior/assertion
  changes: tests call `filter.doFilter(req, res, chain)`, which `OncePerRequestFilter`
  still exposes as the public entry point.
- `config/SecurityConfig.java` -- update the `JwtAuthFilter` import from
  `com.leaveapp.security.JwtAuthFilter` to `com.leaveapp.filter.JwtAuthFilter`.
- `config/AppInitializer.java` -- no change needed; `ApiLoggingFilter`/`RequestIdFilter`
  are wired by Spring bean name (`DelegatingFilterProxy("apiLoggingFilter")` /
  `("requestIdFilter")`), not by import.
- `security/RestAuthEntryPoint.java` -- no change; its comment referencing
  `RequestIdFilter` by name (not import) stays accurate regardless of package.
