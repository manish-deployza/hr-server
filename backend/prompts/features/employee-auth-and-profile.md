# Employee Auth & Profile

## Status: Implemented

**Status update (2026-09-17): `POST /api/auth/change-password`/`ChangePwdForm` removed.**
No live frontend page called it (only stale `target/` build artifacts referenced it) --
a password change now goes entirely through the forgot-password flow
(`prompts/features/forgot-password.md`), which is already built and tested. Confirmed
with the user. Every `change-password` mention below is historical record of what was
originally built, not current behavior.

Built as planned below, with two bugs found and fixed during end-to-end verification
(a full root+servlet Spring context booted against H2, exercising every endpoint over
real HTTP via MockMvc plus the actual filter chain -- not just repo tests):

1. **`SecurityConfig` only `permitAll`'d `POST /api/auth/login`, not `/logout`.** The
   plan's own Edge Cases section said logout should work without a valid session, and
   the controller was built that way -- but the route-level rule still required
   authentication, so Spring Security rejected an already-logged-out client's logout
   call with 401 before the controller ever ran. Fixed: both routes are now `permitAll`.
2. **Failed-login lockout never actually persisted.** `EmpService.verifyLogin`
   incremented `failedAttempts` on a bad password, then threw -- and since it runs
   inside `AuthFlow.login()`'s transaction (`REQUIRED` propagation joins it), the throw
   rolled back the whole transaction, discarding the increment every time. Fixed by
   moving the counter write into `EmpService.recFailed`, called through a
   self-injected proxy with `@Transactional(propagation = REQUIRES_NEW)` so it commits
   independently of the caller's outcome. See `prompts/schema.md`'s `employee` table
   note for the detail.

Everything else matches the plan below as written.

**`/deployza-code:review` (run 1)** found 8 further violations, all fixed:
`GlobalExceptionHandler` no longer leaks raw exception text/internal IDs to the client
(`onError`/`onBadJson`/`onNotFound` now return fixed generic summaries, full detail still
logged); `EmpPojo` gained a `@PreUpdate` hook so `updated_at` actually changes on
every write, and the `manager_id`/`status` indexes the doc already claimed existed now do;
`LoginForm`/`ChangePwdForm` carry `byte[]` password fields via a custom Jackson
deserializer instead of `String`; `recordFailedAttempt` was renamed to `recFailed` (15-char
method-name limit); eight over-length variable names were shortened (`employeeFlow` →
`empFlow`, etc.); `EmpController` and the rest of `AuthController`'s handlers gained
request-entry logging. The employee table also picked up a proper Flyway migration
(`V2__create_employee.sql`) instead of repeating the hand-bootstrap used for `session` --
see `prompts/schema.md` and `prompts/libraries.md` for the Flyway setup itself, including
why it's pinned to 11.x rather than the latest 13.x.

## Overview

Cookie-based session authentication and self-service profile for employees, covering the
`Auth & session` and `Employee self-service` (`GET`/`PATCH me` only) sections of
`leave-frontend/fronted/API-REQUIREMENTS.md`. Introduces `EmpPojo` -- the first real
domain entity in this codebase. Everything else (leave, HR employee CRUD, one-on-ones,
appraisals) is separate, later work.

## Edge Cases

- **Authorization.** `POST /api/auth/login` is the only public route. Everything else in
  this feature requires an authenticated session (`401` via `requireUserId()`, plus the
  route-level `authorizeHttpRequests` rule as defense in depth).
- **Exited employees.** `status = EXITED` blocks both a fresh login and continued use of
  an already-issued session -- checked in `EmpService.verifyLogin` (login) and in
  `UserAuthFilter` (every subsequent request, via a lightweight status check).
- **Account-enumeration / generic failure.** Unknown email, wrong password, and a locked
  account all raise the same message from `verifyLogin`. Matches
  `API-REQUIREMENTS.md`'s "Generic failure -- frontend shows 'Invalid credentials.'
  regardless of the actual error."
- **Lockout.** `failedAttempts` increments on each bad password; at
  `app.login.max-failed-attempts` the account gets `lockedUntil = now + app.login.lockout-minutes`.
  Time-based, self-clearing -- there's no HR unlock endpoint yet to build a manual-unlock
  path against. Checked *before* the password comparison, per the security baseline.
  Resets to `0`/`null` on a successful login.
- **Idempotency.** `POST /auth/logout` never errors: revoking an already-revoked or
  nonexistent session is a no-op, and logout doesn't even require a valid session --
  it reads whatever cookie is present (if any) via `AbstractController.readSessionToken`
  and clears it regardless. Re-submitting a `PATCH /employees/me` with the same body is
  naturally idempotent (plain field overwrite).
- **Concurrency.** Single-session policy (`SessionService.create`, already built) revokes
  prior active sessions before inserting the new one, inside one transaction. There's a
  small window between the revoke-query and the insert where two concurrent logins could
  both leave a session active -- accepted as a low-impact, unlikely race; not solved with
  row locking here.
- **Password transport.** `LoginForm`/`ChangePwdForm` fields are necessarily `String` --
  Jackson can't populate a `byte[]` from a JSON string directly. Each controller method
  converts to `byte[]` in its first line and never retains the `String` beyond that,
  matching the spirit of the byte-array-password rule at the one boundary where a
  transient `String` is unavoidable.
- **Dangling `manager_id`.** If an employee's `manager_id` points at a row that's gone
  (shouldn't happen with no deletes yet, but no DB-level FK either -- see Database
  Indexes), `managerName` resolves to `null` rather than a `500`.

## REST API

All under the existing cookie-session model (`app.cookie.name`); `{}` bodies are literal
empty JSON objects (`Map.of()`), matching `API-REQUIREMENTS.md` exactly.

| Method | Path | Body | Response | Auth |
|---|---|---|---|---|
| POST | `/api/auth/login` | `LoginForm {email, password}` | `{}`, sets cookie | public |
| GET | `/api/auth/me` | -- | `SessionData` | session |
| POST | `/api/auth/change-password` | `ChangePwdForm {currentPassword, newPassword}` | `{}` | session |
| POST | `/api/auth/logout` | -- | `{}`, clears cookie | none required (see Edge Cases) |
| GET | `/api/auth/verify-email` | `?token=` | `302` to `app.verify.redirect-base?verified=1\|0` | public (token is the authority) |
| POST | `/api/auth/resend-verification` | -- | `{}` | session |
| GET | `/api/employees/me` | -- | `SessionData` (identical shape to `/auth/me`) | session |
| PATCH | `/api/employees/me` | `ProfileForm {phone, address}` | `SessionData` | session |

### Email verification

Added after the original build (JWT era). `user.email_verified` (boolean, default false)
tracks the state; `SessionData.emailVerified` exposes it.

- **Token.** `JwtService.issueEmailVerify(userId)` mints a purpose-scoped signed token,
  24h TTL. `parseEmailVerify` rejects a normal session token.
- **`GET /api/auth/verify-email?token=`** (public) — `AuthFlow.verifyEmail` →
  `UserService.markVerified`, then 302 to the frontend (`?verified=1` ok, `?verified=0`
  bad/expired). Clicked straight from a mail client, so no session.
- **`POST /api/auth/resend-verification`** (session) — `AuthFlow.resendVerification(userId)`
  re-mails the link to the caller's own address. `400` if already verified. `400` if
  called again within `app.verify.resend-cooldown-seconds` (default 60) — a per-user
  in-memory cooldown (`ConcurrentHashMap` on `AuthFlow`; per-instance is enough for the
  single-WAR deployment), stamped on request not on delivery. Mail-send failure is
  swallowed by `VerificationMailer` (best-effort); the endpoint still returns `{}`.
- **The email itself** (`VerificationMailer`, shared by signup, HR-created employee, and
  resend) carries an "Account details" block — name, employee code, designation,
  department, email, from the `employee` profile row, blanks skipped, HTML-escaped in the
  HTML body — under a name-personalised subject (`"<name>, verify your email address for
  Leave"`, or a generic line when no name is on file).

`SecurityConfig` change: narrow `permitAll` from `/api/auth/**` to exactly
`POST /api/auth/login`; everything else falls under the existing `.anyRequest().authenticated()`.
Add a custom `AuthenticationEntryPoint` (`RestAuthEntryPoint`, new, `security` package) so a
filter-level `401` is `{"summary": "..."}` JSON instead of an empty body -- the frontend
reads `body.summary` unconditionally on every non-2xx response.

## Workflow

- **`AuthFlow.login(email, rawPwd) -> LoginData`** -- calls `EmpService.verifyLogin`
  (lookup + lockout + password + status check, throws on any failure), then
  `SessionService.create(employeeId, ttl)`. Returns `LoginData {token}` (new, tiny `*Data`
  class) so the controller can write the cookie -- the actual HTTP response body is `{}`,
  not the token.
- **`EmpFlow.getProfile(userId) -> SessionData`** -- assembles the full session
  shape: `EmpService.findById(userId)`, `EmpService.existsManager(userId)` for
  `isManager`, and `EmpService.findByIdOrNull(managerId)` for `managerName` (null if
  no manager or manager row missing). `organizationName` comes from `app.org.name`
  (`Environment`), not a repo call. Shared by both `GET /auth/me` and `GET /employees/me`.
- **`EmpFlow.updateProfile(userId, form) -> SessionData`** -- calls
  `EmpService.updateProfile(userId, phone, address)`, then re-assembles via the same
  logic as `getProfile`.
- `POST /auth/change-password` and `POST /auth/logout` are trivial single-domain cases --
  `AuthController` calls `EmpService`/`SessionService` directly, no Flow.

## Business Logic

- **`EmpService.verifyLogin(email, rawPwd) -> EmpPojo`**: find by email (null ->
  generic fail) -> check `status != EXITED` (fail) -> check `lockedUntil` still in the
  future (fail) -> `BCryptPasswordEncoder.matches` against `passwordHash` -> on mismatch,
  increment `failedAttempts`, lock if threshold hit, fail -> on match, reset
  `failedAttempts`/`lockedUntil`, return the employee. All failure paths throw one
  `NotAuthenticatedException` with the same message. `rawPwd` zeroed in a `finally`.
- **`EmpService.changePassword(userId, currentPwd, newPwd)`**: load employee,
  verify `currentPwd` against `passwordHash`, reject `newPwd` under 8 bytes
  (`BadRequestException`), hash and store `newPwd`. Both byte arrays zeroed in
  `finally`. (No `forcePasswordReset` field to clear -- see [multi-tenancy.md](multi-tenancy.md)
  for its removal.)
- **`EmpService.updateProfile(userId, phone, address)`**: plain field overwrite, no
  format validation beyond the DB column (both are free-text per the API doc).
- **`UserAuthFilter` change**: after `SessionService.validate(token)` succeeds, calls
  `EmpService.findByIdOrNull(session.getEmployeeId())`. If missing or
  `status == EXITED`, treat as unauthenticated (leave `SecurityContext` empty). Otherwise
  populates `UserPrincipal.roles = List.of(employee.getRole().name())`.

## POJOs

**`EmpPojo`** (new) -- mirrors `Prompts/02-database-schema.md`'s `employee` table,
minus `organization_id` (multi-tenancy out of scope, per `API-REQUIREMENTS.md`), **plus**
two additions:

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | bigint | no | `@TableGenerator`, allocationSize 100 |
| `employee_code` | varchar | no | unique |
| `name` | varchar | no | |
| `email` | varchar | no | unique (global, since single-org) |
| `phone` | varchar | yes | |
| `address` | varchar | yes | **not in `02-database-schema.md`** -- required by `API-REQUIREMENTS.md`'s session object and `PATCH /employees/me`; that doc is the stated source of truth over `Prompts/` where they disagree |
| `password_hash` | varchar | no | BCrypt |
| `date_of_joining` | date | no | |
| `date_of_exit` | date | yes | |
| `employee_type` | varchar (enum) | no | `REGULAR`, `INTERN` |
| `designation` | varchar | yes | |
| `department` | varchar | yes | |
| `manager_id` | bigint | yes | plain FK column, self-referencing, no `@ManyToOne` |
| `basic_salary` | decimal(12,2) | yes | |
| `role` | varchar (enum) | no | `EMPLOYEE`, `HR` |
| `status` | varchar (enum) | no | `ACTIVE`, `ON_NOTICE`, `EXITED` |
| `failed_attempts` | int | no, default 0 | **not in `02-database-schema.md`** -- added for the account-lockout requirement in the security baseline, which the schema doc doesn't cover |
| `locked_until` | datetime | yes | **not in `02-database-schema.md`**, same reason |
| `created_at` | datetime | no, `updatable=false` | |
| `updated_at` | datetime | no | |

No `@ManyToOne`/`@OneToMany` -- `managerId` stays a plain `Long`, matching both
`02-database-schema.md`'s stated ORM convention and this project's own
`jpa-persistence.md`.

## Database Indexes

- `manager_id` -- every "get my team" query filters on it (future manager feature); add
  now while the column is being created.
- `email` -- login lookup (`findByEmail`), and it's already unique so the constraint
  creates the index for free.
- `status` -- filters like `status != EXITED` in the auth filter's lookup.
- No DB-level `FOREIGN KEY` on `manager_id`, matching `02-database-schema.md`'s constraint
  policy (kept there for `employee.manager_id`, actually -- but this project's own
  `jpa-persistence.md`/`layered-flow.md` default is "prefer no FK constraints" and a
  self-referencing FK on a table with no delete path yet isn't protecting against a real
  bug today; noting the conflict, defaulting to this project's own stated convention).

## Database

`EmpRepo extends JpaRepository<EmpPojo, Long>`:
- `findByEmail(String email) -> Optional<EmpPojo>`
- `existsByManagerId(Long managerId) -> boolean`

`EmpRepoTest` + `EmpTestData` per `jpa-persistence.md` conventions: one test per
repo method, null-constraint tests for every `nullable = false` column, duplicate + scope
tests for the `email` and `employee_code` unique constraints.

## External Libraries

- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (new) -- `SessionData` carries
  `LocalDate` (`dateOfJoining`/`dateOfExit`); Jackson has no `java.time` support without
  this module. Registered on the `MappingJackson2HttpMessageConverter`'s `ObjectMapper` in
  `WebConfig`, with `WRITE_DATES_AS_TIMESTAMPS` off so dates serialize as `"YYYY-MM-DD"`
  per `API-REQUIREMENTS.md`'s conventions. `BCryptPasswordEncoder` needs no new
  dependency -- `spring-security-crypto` is already transitive via `spring-security-web`
  (confirmed via `mvn dependency:tree`).

## Configuration Parameters

| Property | Default | Purpose |
|---|---|---|
| `app.org.name` | (set per deployment) | `SessionData.organizationName` -- config-driven since multi-tenancy is out of scope |
| `app.login.max-failed-attempts` | `10` | lockout threshold |
| `app.login.lockout-minutes` | `15` | how long a lockout lasts |
| `app.verify.resend-cooldown-seconds` | `60` | min gap between `POST /api/auth/resend-verification` calls from one user |

Minimum password length (8 bytes) is a hardcoded constant in `EmpService`, not a
config property -- not something this app needs to tune per environment.

## Relevant Files

- `pojo/EmpPojo.java` -- new entity, see POJOs above
- `repo/EmpRepo.java` -- new repository
- `service/EmpService.java` -- new: `verifyLogin`, `changePassword`, `updateProfile`, `findById`, `findByIdOrNull`, `existsManager`
- `workflow/AuthFlow.java` -- new: `login`
- `workflow/EmpFlow.java` -- new: `getProfile`, `updateProfile`
- `rest/AuthController.java` -- new: login, me, change-password, logout
- `rest/EmpController.java` -- new: get/update me
- `rest/AbstractController.java` -- modify: add `readSessionToken`
- `security/UserAuthFilter.java` -- modify: populate `UserPrincipal.roles`, reject `EXITED`
- `security/RestAuthEntryPoint.java` -- new: `ErrorData`-shaped 401 for filter-level auth rejection
- `config/SecurityConfig.java` -- modify: narrow `permitAll`, wire `RestAuthEntryPoint`
- `config/WebConfig.java` -- modify: register `JavaTimeModule` on the Jackson converter
- `model/form/LoginForm.java`, `ChangePwdForm.java`, `ProfileForm.java` -- new
- `model/data/SessionData.java`, `LoginData.java` -- new
- `pom.xml` -- add `jackson-datatype-jsr310`
- `app.properties` / `test.properties` -- add the three config properties above
- `prompts/schema.md` -- add the `employee` table, flag the two deviations from `02-database-schema.md`
- `prompts/libraries.md` -- add `jackson-datatype-jsr310`
- `src/test/java/.../repo/EmpRepoTest.java`, `EmpTestData.java` -- new
