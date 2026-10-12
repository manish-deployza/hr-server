# Multi-Tenancy

## Status: Planning (organization + orgKey-resolution slice implemented)

## Progress

**Implemented (2026-08-26):** the `organization` table/entity/repo/service slice --
`OrgPojo`, `OrgRepo`, `OrgService` (`findById`,
`findByOrgKeyOrNull`, `create`), migration `V12__create_organization.sql` (creates the
table, seeds one default tenant row, bumps the id-generator past it), a new
`ConflictException` mapped to `409` in `GlobalExceptionHandler` (first use of that status
in this codebase, needed by `create`'s orgKey-collision check), and repo/service tests.
`OrgService.create`'s signature is `(name, orgKey, hrNotifyEmail)` -- three
params, since `hr_notify_email` is `NOT NULL` and has no sensible default.

**Implemented (2026-08-31):** `POST /api/hr/employees` -- an `ORG_ADMIN`/`HR`
creates one `EMPLOYEE`/`HR` account (a `user` row + 1:1 `employee` row) in their
own org, in one transaction. Password required (no invite-link flow). First endpoint to
write `HR`/`EMPLOYEE` to `user.role`. New: `HrEmpController`, `HrEmpFlow`,
`EmpForm`; `AbstractController.requireAnyRole`/`hasRole`; `UserService.existsInOrgByEmail`/
`existsInOrg`; `EmpService.createProfile`; two `UserRepo` `exists…` finders. No
migration. See `prompts/features/hr-employee-management.md` (get-by-id/update/soft-delete/
reactivate/CSV/enum-conversion all deferred there).

**Implemented (2026-08-31, same day):** `GET /api/hr/employees` -- lists every account
in the caller's org as `EmpRowData[]`, unpaged/unfiltered (HR screen filters
client-side). `HrEmpFlow.list` zips `UserService.findByOrg` + `EmpService.findByOrg`
by id (two queries, no per-row lookups). New: `EmpRowData`; `findByOrg` on both
services; `findByOrganizationId` on `UserRepo` + `EmpRepo`. No migration; org_id
prefix of `uk_user_org_email` / `uk_employee_org_code` covers the new finders.

**Implemented (2026-08-31, same day):** `PATCH /api/hr/employees/{id}` -- edits one
account in the caller's org. Reuses `EmpForm`, PATCH semantics (`null`/blank =
unchanged, so nullable columns can't be cleared). Admin password reset via new
`UserService.setPassword` (no current-password check). `status` (`ACTIVE`/`ON_NOTICE`/
`EXITED`) now written; `is_active` deliberately left for the deferred soft-delete
endpoint. `HR` caller may modify `EMPLOYEE` rows only -- controller passes a
`callerIsOrgAdmin` boolean to `HrEmpFlow.update` (Flow still doesn't read the
security context). Cross-org / unknown id -> `404`, no existence leak. Returns the
updated `EmpRowData`. Controller's two payload-role checks extracted to a shared
`checkPayloadRole`. No migration.

**Implemented (2026-08-31, same day):** `DELETE /api/hr/employees/{id}` -- soft-delete:
`is_active=false`, `status=EXITED`, `date_of_exit=today`. No hard delete (leave history
FKs). Idempotent (already-`EXITED` -> no-op, keeps original exit date). Self-deactivation
blocked in the controller (`{id}` == `requireUserId()` -> 400). Shares `PATCH`'s target
guard, extracted to `HrEmpFlow.loadTargetInOrg` (cross-org/unknown -> 404;
`HR` caller limited to `EMPLOYEE` targets). No `verifyLogin` change -- it already
rejects `EXITED`; the deferred `!isActive()` guard stays deferred. Body is `Map.of()`.
The paired `PATCH .../reactivate` is not built (user asked for `DELETE` only). No
migration.

**Implemented, then deleted the same day:** a `platform_admin` table/entity/repo/service
slice was built and tested, then removed entirely per an explicit user decision -- see
Correction (2026-08-26, later) below. Not present in the codebase.

**Implemented, then reworked the same day:** `TenantContext` (plain `ThreadLocal<Long>`)
was built alongside a `TenantResolutionFilter` that resolved tenant from the request's
server name on every request. That filter is now **deleted** -- see Correction below;
this design does not resolve tenant per-request at all. `TenantContext` itself stays, as
a holder populated by `JwtAuthFilter` from the JWT claim (not yet built).

**Implemented (2026-08-26):** `force_password_reset` removed from `EmpPojo`,
`SessionData`, `EmpService.changePassword`, `EmpFlow`, and `EmpTestData`,
plus migration `V13__drop_force_password_reset.sql`. A `NULL` `password_hash` will be
the "unusable until set" signal once employee-creation-without-a-password exists (not
yet built -- `password_hash` stays `NOT NULL` on the table until then, since nothing
creates a row without one yet).

**Implemented (2026-08-26):** `email_verified` (boolean, default `false`) added to
`EmpPojo` (migration `V14__add_email_verified_to_employee.sql`). Nothing sets it
`true` yet -- the intended trigger is completing the (not yet built) set-password link,
on the theory that receiving and clicking an emailed link is itself proof of inbox
ownership. **Open gap, not yet resolved:** whether the self-signup `ORG_ADMIN` should
start `true` (no email round-trip happened, but they typed their own address and it's
presumably real) or `false` (no verification step actually ran) -- defaulted to `false`
for now since this design has no signup-time verification email at all; revisit if
that matters before this ships.

**Implemented (2026-08-27):** signup no longer collects `orgName`/`name` -- confirmed
with the user, "less friction." `SignupForm` is now `{orgKey, email, password}`
only; `AuthFlow.signup(orgKey, email, rawPwd)` passes `null` through for both.

**Implemented (2026-09-11):** signup now also collects `country` -- confirmed with the
user: a dropdown of all countries, so the value is required. `SignupForm` gains a
`country` field (ISO 3166-1 alpha-2, matching `AddressPojo.country`'s convention);
`AuthFlow.requireCountry` rejects a blank value or one not in `Locale.getISOCountries()`
(no bundled country list needed) and upper-cases it before `OrgService.create`
stores it on `organization.country`. That column is nullable at the DB (same
hbm2ddl.auto=update constraint noted for `name`) -- "required" is enforced
only in `AuthFlow`, the sole caller that collects it; `OrgService.create`'s
original 3-arg signature is kept as an overload defaulting `country` to `null` so the
many non-signup test callers that seed orgs are untouched.
`organization.name` and `employee.name` are now nullable (migration
`V19__make_name_nullable.sql`) rather than deriving a placeholder value -- simplest
fix for the `NOT NULL` constraint, no placeholder-generation logic to maintain. Both
fields are `NULL` until set later via a settings feature (not yet built).
`orgKey` is unaffected -- still required, still the only thing login can resolve
tenant from.

**Implemented (2026-08-31):** identity split into a base `user` table + a 1:1
`employee` detail table, and all timestamps pinned to UTC.

- **`user`** (`UserPojo`/`UserRepo`/`UserService`, migrations `V21`/`V22`) carries
  auth only: `email` (unique per org), `password_hash`, `role`, `status`, `is_active`,
  `email_verified`, `failed_attempts`, `locked_until`, timestamps. Table name `user`
  is always a quoted identifier (keyword in H2, function name in MySQL) --
  `@Table(name = "`user`")`, backticks in the migration DDL. Surrogate `BIGINT` PK
  (`@TableGenerator`); email is a unique login key, not the PK.
- **`employee`** keeps the HR profile (code, name, phone, address, joining/exit dates,
  `employee_type`, designation, department, `manager_id`, `basic_salary`). Its PK
  **is** the `user` id (`user_id`, no generator). `V22` reshaped the old combined
  table in place (pre-launch, `employee` was empty).
- **`admin`/`hr`** detail table: **not built** -- no admin-only column exists yet.
  Same 1:1 `user_id`-PK shape when one does; an `HR` keeps its `employee` row
  regardless (they take leave). The `role` -> detail-table switch would go in
  `EmpFlow.toData`, the single current call site.
- **Leave domain** (`leave_request`, `leave_ledger_entry`, `leave_request_approval`,
  `notification`, `audit_log`) now FKs to `user.id`: `employee_id` -> `user_id`,
  `approver_employee_id` -> `approver_user_id` (migration `V23`).
- **`role`** is still the sole RBAC signal (JWT `role` claim -> `UserPrincipal.roles`);
  `AuthFlow.signup` -> `UserService.createUser(..., "ORG_ADMIN")` then
  `EmpService.createOrgAdminProfile`. The not-yet-built HR/employee-creation
  endpoints add a `PENDING` `role` sentinel for the create-row-then-assign-role gap.
- **UTC**: `java.time.Instant` on every entity + `hibernate.jdbc.time_zone=UTC`
  (`DBConfig`, `app.properties`/`test.properties`). See `prompts/schema.md`.
- **Not touched:** `SessionData`/`ProfileForm` field names and the `/api/employees/*`
  routes (frontend contract) -- `EmpFlow.toData` merges `user` + `employee`
  into the same `SessionData` shape.

**Resolved (2026-08-26):** the user chose to add a separate `is_active` boolean to both
`organization` and `employee`, kept alongside the existing `status` enums (not
replacing them) -- confirmed despite the two-columns-can-disagree risk flagged above.
Implemented: `OrgPojo.active`/`EmpPojo.active` (Lombok primitive-boolean
convention: field `active`, not `isActive`, so the generated accessor is `isActive()`/
`setActive()` -- matches `NotificationPojo.read`/`isRead()` elsewhere in this codebase),
migrations `V15__add_is_active_to_employee.sql`,
`V16__add_is_active_to_organization.sql`. **Trap already hit and fixed**: Hibernate
sends every mapped column explicitly on `INSERT`, so a column's SQL `DEFAULT` never
applies to a JPA-created row -- `OrgService.create()` now sets `active(true)`
explicitly; any future employee-creation code must do the same for
`EmpPojo.active` or every app-created row would silently be inactive.
"Delete" an employee/org going forward means `active = false`, never a SQL `DELETE`.

Not yet built: JWT (`JwtService`/`JwtAuthFilter`), the self-service signup endpoint
(`POST /api/auth/signup`), HR/ORG-admin employee-creation endpoints (manual + CSV
import), the set-password-link flow (`invite_token_hash`/`invite_token_expires_at`,
email delivery), and `organization_id` on `employee`/every other tenant-owned table.
`OrgService` has no caller yet -- nothing in the running app resolves a tenant
or creates an org through it until the signup/login slice lands.

## Correction history

**2026-08-26, first correction:** the original version of this doc specified shared
schema with `organization_id` on only three tables, an `orgCode` login-form field, the
existing cookie-session auth kept as-is, and org creation out of scope (seeded by
migration only). That was superseded in favor of matching
`leave-frontend/Prompts/multi-tenancy.md` (subdomain resolution, JWT, `organization_id`
on every table, `platform_admin` provisioning) -- confirmed with the user at the time.

**2026-08-26, later the same day -- corrected again:** the frontend's actual, current
`leave-frontend/Prompts/multi-tenancy.md` (read in full only after the subdomain design
was already partly built) turns out to disagree with the "first correction" above on
several points. Reading it revealed:

- **No subdomain, ever.** Tenant is resolved **once, at login/signup**, from an
  `orgKey` field typed into the form -- never from the request `Host`/URL. There is
  no per-request re-resolution or cross-check; the JWT's `organizationId` claim is the
  *sole* tenant signal for every request after login. This is a deliberate,
  documented reduction in defense-in-depth (a leaked token is trusted for its lifetime)
  traded for not needing per-tenant DNS/subdomain provisioning on a single-origin static
  frontend. **`TenantResolutionFilter` (Host-header based) is deleted** as a result --
  wrong mechanism entirely, not just unwired.
- **JWT travels in a cookie, not a bearer token.** `leave-frontend/fronted/API-REQUIREMENTS.md`'s
  own stated convention -- "Auth: cookie-based, not a bearer token... `apiFetch` never
  reads/attaches an `Authorization` header" -- still applies. Signup/login set an
  `HttpOnly` cookie carrying the JWT, exactly like today's session cookie; `JwtAuthFilter`
  reads that cookie (via `AbstractController`/`CookieUtil`, same as `UserAuthFilter`
  today), not an `Authorization: Bearer` header. Corrected here before any JWT code was
  written against the wrong transport.
- **Org creation is self-service signup**, not a `platform_admin`-gated bootstrap call.
  Confirmed with the user, and the already-built `platform_admin` table/entity/repo/
  service was deleted as a result (the frontend doc would have kept it ops-only for
  suspend/reactivate/list; the user explicitly chose to drop it entirely instead --
  a deliberate deviation from the frontend doc, not an oversight).
- **Roles**: the frontend doc uses the existing 2-value `EMPLOYEE`/`HR` enum
  (signup just creates an `HR`). **The user explicitly chose a 3-tier model
  instead**: `ORG_ADMIN` (created by signup) → creates `HR` → creates `EMPLOYEE`.
  This is a deliberate deviation from the frontend doc, confirmed with the user, kept as
  the design going forward.
- **Org-key character set**: the frontend doc's regex is `^[a-z0-9_]{1,64}$`
  (underscore only). **The user explicitly wants both `-` and `_` allowed** --
  `^[a-z0-9_-]{1,64}$`, already implemented in `OrgService`. Another deliberate,
  confirmed deviation.
- **`force_password_reset` is removed entirely**, replaced by a `NULL` `password_hash` +
  (future) `invite_token_hash`/`invite_token_expires_at` columns. Confirmed with the
  user and already implemented (see Progress above) -- this reaches back into the
  already-shipped `employee-auth-and-profile` feature, not just new code.

The rest of this doc is rewritten to match the corrected design. Everything under
"Decisions" through "Relevant Files" below reflects the current understanding, not the
subdomain/`platform_admin`-bootstrap version.

## Overview

Converts leave-backend from single-tenant to multi-tenant: shared schema with
`organization_id` on every business table, tenant resolved **once at login/signup** from
a user-typed `orgKey` (never the URL/subdomain), JWT carried in an `HttpOnly` cookie
(replacing the DB-backed `session` table), and public self-service signup for creating a
new organization (no platform-admin gate). A 3-tier role model
(`ORG_ADMIN`/`HR`/`EMPLOYEE`) and an emailed set-password link for HR-added
employees are deliberate departures from `leave-frontend/Prompts/multi-tenancy.md`,
confirmed with the user -- see Correction history above for exactly where and why this
doc parts ways with that one.

## Decisions

1. **Isolation model**: shared schema, `organization_id BIGINT NOT NULL FK` on every
   tenant-owned table -- not schema- or DB-per-tenant.
2. **Tenant resolution**: a user-typed `orgKey`, validated `^[a-z0-9_-]{1,64}$`
   (hyphen *and* underscore, both allowed -- a deliberate widening of the frontend doc's
   underscore-only regex), resolved **once, at login or signup**. No per-request
   `Host`/subdomain resolution and no per-request cross-check of any kind -- the JWT's
   `organizationId` claim is the sole tenant signal for every request after login.
3. **Auth**: JWT replaces the cookie-backed `session` table, but **stays cookie-transported**
   -- an `HttpOnly` cookie carries the JWT, matching `API-REQUIREMENTS.md`'s stated
   "cookie-based, not a bearer token" convention. `organizationId` is a JWT claim
   alongside `sub`/`role`/`isManager`.
4. **Tenant enforcement**: a Hibernate `@Filter` (`organization_id = :organizationId`)
   enabled per-request from `TenantContext` (populated by `JwtAuthFilter` from the JWT
   claim), so every repository query is scoped by the ORM itself, not by convention.
5. **Uniqueness**: `employee.email` and `employee.employee_code` become unique per
   organization (composite), not globally. Same pattern applies to `leave_type.code` and
   `public_holiday.holiday_date`.
6. **Provisioning: self-service signup, no platform admin.** `POST /api/auth/signup`
   creates the `organization` row and the caller's own `employee` row (`role=ORG_ADMIN`)
   in one transaction, using the password the signer-upper chose themselves -- no temp
   password, nothing to email, immediate JWT-cookie session. `platform_admin` does not
   exist in this design (deliberately deleted -- see Correction history).
7. **Roles, 3-tier**: `ORG_ADMIN` (created only by signup) → creates `HR`
   employees → `HR` creates `EMPLOYEE` rows (manually or via CSV import).
   Whether `ORG_ADMIN` can also directly create `EMPLOYEE` rows (skipping `HR`) is
   **not yet decided** -- flag for confirmation when the creation endpoints are built.
8. **Employee password setup**: the org creator (signup) is the only path that sets a
   password directly. Every employee created by an `HR` (single or CSV) gets
   `password_hash = NULL` and an emailed set-password link instead of a temp password --
   `force_password_reset` is gone (see Correction history); a `NULL` hash is simply
   unusable for login until the link is used.
9. **Pre-launch**: no real production data exists yet, so migrations backfill existing
   local/dev/test rows to one seeded default organization in place, rather than needing
   a careful nullable-then-backfill-then-tighten multi-release rollout.

## Edge Cases

- **Unknown/suspended org at login.** Same generic `401` ("Invalid credentials") as a
  wrong password or unknown email -- not a distinct `404`/`403`. Resolving the org is
  part of the same non-enumerating failure path, not a separate pre-check with its own
  status code.
- **No per-request tenant cross-check.** Accepted, documented tradeoff (matches the
  frontend doc): a validly-signed JWT's `organizationId` claim is trusted as-is for the
  token's lifetime. This does not weaken cross-tenant *data* isolation, which the
  Hibernate `@Filter` still enforces on every query regardless of how the claim got
  there -- it only means a leaked/replayed token isn't independently re-checked against
  a second signal (there is no second signal in this design).
- **Login identity.** `email` is no longer globally unique, so login resolves
  `orgKey → organizationId` first, then `EmpService.verifyLogin(organizationId,
  email, rawPwd)` (lockout check → password check, unchanged from today). Same generic
  failure message throughout.
- **Signup vs. login orgKey collision.** `OrgService.create` already returns
  `409` on a taken `orgKey` (implemented). Login's "unknown orgKey" case is
  folded into the generic `401` instead (see above) -- the two endpoints intentionally
  behave differently here: signup needs to tell the user their chosen name is taken;
  login must not leak whether an org exists at all.
- **Two organizations, the same email.** Explicitly supported -- the point of the
  change. Covered by a repo test pair per `jpa-persistence.md`'s convention.
- **Cross-org `manager_id`.** No DB-level FK on `employee.manager_id` (unchanged).
  Flagged as a check to add when employee-creation is built (`manager_id` must resolve
  to an employee in the same `organization_id`).
- **Role-creation authorization** (not yet built): `ORG_ADMIN` creating an `HR`,
  and `HR` creating an `EMPLOYEE`, both need the caller's role checked against the
  role being assigned -- an `HR` must not be able to create another `HR` or
  an `ORG_ADMIN`. Whether there can be more than one `ORG_ADMIN` per org, and whether an
  `ORG_ADMIN` can create `EMPLOYEE` rows directly, are open (see Decisions #7) --
  confirm before building the endpoint.
- **CSV import row handling** (not yet built): per-row processing; a duplicate email
  within the org is rejected by the existing `(organization_id, email)` unique
  constraint like any other duplicate -- there is no separate "does this email already
  have a password" check to build, since a genuinely duplicate row simply fails
  uniqueness regardless of whether the existing row has completed setup.
- **Set-password link** (not yet built): token stored only as a SHA-256 hash + expiry
  (`invite_token_hash`, `invite_token_expires_at`) -- raw token only ever in the email.
  Expired/invalid/already-used token → generic `400`, no account-enumeration detail.
  Re-sending a link regenerates and overwrites the token; only one is ever live per
  employee.
- **Stateless JWT means no server-side logout.** With no `session` table, `logout`
  becomes a client-side cookie clear -- there's nothing left for the server to revoke.
  `POST /api/auth/logout` stays as a cookie-clearing no-op (matches today's idempotent
  behavior, just clearing a JWT cookie instead of revoking a DB row).
- **`notifications.md` still doesn't exist.** The set-password email step is a stub
  (log the link server-side, or return it in a dev-only response field) until that doc
  exists to specify the real delivery channel -- same treatment the old bootstrap-email
  step had before this correction.

## REST API

Assumed shapes for not-yet-built endpoints, inferred from the frontend's canonical
design plus the confirmed deviations above -- **not yet confirmed with the user**,
flagged individually. Confirm before implementing.

| Method | Path | Body | Response | Auth |
|---|---|---|---|---|
| POST | `/api/auth/signup` (implemented) | `{orgKey, email, password, country}` -- `orgName`/`name` deliberately not collected (less friction), set later via settings. `country` is required, ISO 3166-1 alpha-2 (frontend renders a dropdown of all countries), validated against the JDK's own list (`Locale.getISOCountries()`) | `{}`, JWT cookie set | public |
| POST | `/api/auth/login` | `LoginForm {orgKey, email, password}` | `{}`, JWT cookie set | public |
| GET | `/api/auth/me` | -- | `SessionData` | JWT cookie |
| POST | `/api/auth/change-password` | `ChangePwdForm {currentPassword, newPassword}` | `{}` | JWT cookie |
| POST | `/api/auth/logout` | -- | `{}`, clears cookie | none required (unchanged behavior) |
| POST | `/api/auth/set-password` *(assumed, unconfirmed)* | `{token, password}` | `{}`, JWT cookie set | public (token is the authority) |
| GET / PATCH | `/api/employees/me` | -- / `ProfileForm` | `SessionData` | JWT cookie |
| POST | `/api/hr-admins` *(assumed path/shape, unconfirmed)* | `{name, email}` | `{id}` | `ORG_ADMIN` only |
| POST | `/api/employees` *(assumed, unconfirmed)* | `{name, email, managerId, ...}`, no password field | `{id}` | `HR` (+`ORG_ADMIN`?) |
| POST | `/api/employees` *(CSV import, assumed)* | one call per parsed CSV row, same shape as above -- matches the existing single-tenant frontend's actual bulk-upload behavior (client parses the CSV, no separate bulk endpoint) rather than inventing a multipart bulk-import endpoint | `{id}` per call | `HR` (+`ORG_ADMIN`?) |

`LoginForm` gains `orgKey` (was previously unchanged in the pre-correction version
of this doc, which wrongly assumed the org came from the URL).

## Workflow

*(Not yet built -- signatures below are the design, not implemented code.)*

- **`AuthFlow.signup(orgKey, email, rawPwd) -> LoginData`** (implemented) -- one
  transaction: `OrgService.create(null, orgKey, email)` (`name` is
  `null` -- not collected, see Progress; the signer-upper's own email doubles as
  `hr_notify_email`), then `EmpService.createOrgAdmin(organizationId, null,
  email, rawPwd)` (`name` is `null`; sets the password directly, `role=ORG_ADMIN`),
  then `JwtService.issue(employee)`. No email step -- the caller is present and just
  typed their own password.
- **`AuthFlow.login(orgKey, email, rawPwd) -> LoginData`** -- resolves
  `organizationId` via `OrgService.findByOrgKeyOrNull(orgKey)` first
  (`null` → generic `401`, same message as any other login failure), then
  `EmpService.verifyLogin(organizationId, email, rawPwd)`, then
  `JwtService.issue(employee)`.
- **`JwtAuthFilter`** (replaces `UserAuthFilter`) -- reads the JWT from the session
  cookie (same cookie transport as today, via `CookieUtil`/`AbstractController`),
  validates signature/expiry via `JwtService`, populates `UserPrincipal` (`userId`,
  `role`, `isManager`, `organizationId`) and `TenantContext` directly from the JWT's
  claims -- no DB lookup per request (short token lifetime bounds the staleness window
  on `status`/role changes, same tradeoff the frontend doc accepts).
- **`EmpFlow.getProfile`/`updateProfile`** -- unchanged in shape; `organizationName`
  comes from `OrgService.findById(principal.getOrganizationId())` instead of
  the removed `app.org.name` config value.

## Business Logic

*(Not yet built except where marked implemented.)*

- **`TenantContext`** (implemented) -- `ThreadLocal<Long>` holding `organizationId`.
  Was set by the now-deleted `TenantResolutionFilter`; will be set by `JwtAuthFilter`
  once that exists, cleared in `finally` either way.
- **`OrgService`** (implemented) -- `findById`, `findByOrgKeyOrNull`,
  `create(name, orgKey, hrNotifyEmail)` (validates `orgKey` against
  `^[a-z0-9_-]{1,64}$` (`BadRequestException`) and uniqueness (`ConflictException`,
  `409`); `status` defaults to `ACTIVE`).
- **`JwtService`** (not yet built) -- `issue(EmpPojo) -> LoginData`, `parse(String)
  -> UserPrincipal`. HS256, secret from `app.jwt.secret` (env var), expiry from
  `app.jwt.expiry-minutes`. `LoginData` carries the raw token for the controller to put
  in a cookie (mirrors today's `AuthController.writeSessionCookie` pattern) -- it is
  **not** returned to the client in the JSON body.
- **`EmpService.verifyLogin(Long organizationId, String email, byte[] rawPwd)`**
  (not yet built) -- lookup becomes `empRepo.findByOrganizationIdAndEmail(...)`; rest of
  the logic (lockout, BCrypt check, `EXITED` check, zeroing `rawPwd`) unchanged.
- **`EmpService.createOrgAdmin(Long organizationId, String name, String email,
  byte[] rawPwd) -> EmpPojo`** (not yet built) -- hashes and stores `rawPwd`
  directly, `role=ORG_ADMIN`, `manager_id=NULL`.
- **`EmpService.createHr`/`createEmployee`** (not yet built, naming
  provisional) -- inserts an `employee` row with `password_hash=NULL`, generates the
  invite token (random value, stores only its SHA-256 hash + expiry in
  `invite_token_hash`/`invite_token_expires_at`), returns the **raw** token once for the
  caller's email step (never persisted in plaintext, never logged).
- **Hibernate tenant filter** (not yet built) -- `@FilterDef`/`@Filter` on every
  tenant-scoped entity, `organization_id = :organizationId`, enabled per-request from
  `TenantContext`. `organization` itself is not filtered (no `organization_id` column
  on it).

## POJOs

**`OrgPojo`** (implemented) -- table `organization`:

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | bigint | no | `@TableGenerator`, allocationSize 100 |
| `name` | varchar | no | display name |
| `org_key` | varchar | no | unique (global) -- resolved at login/signup, `^[a-z0-9_-]{1,64}$` |
| `hr_notify_email` | varchar | no | set at creation (signup's own email initially) |
| `status` | varchar | no | `ACTIVE`, `SUSPENDED` |
| `created_at` | datetime | no, `updatable=false` | |

**`EmpPojo`** (implemented: `force_password_reset` removed. Not yet built: the
rest) and **every other tenant-owned `*Pojo`** (`LeaveTypePojo`,
`LeaveTypeApprovalStepPojo`, `LeaveRequestPojo`, `LeaveRequestApprovalPojo`,
`LeaveLedgerEntryPojo`, `PublicHolidayPojo`, `NotificationPojo`, `AuditLogPojo`,
`ExitSettlementPojo`) -- to add:

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `organization_id` | bigint | no | plain FK column, no `@ManyToOne`, matching this codebase's existing FK convention |

`employee` additionally needs, for the invite-link flow (not yet added -- deferred
until the employee-creation endpoint that populates them is actually built, per YAGNI):

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `invite_token_hash` | varchar | yes | SHA-256 hex of the raw token; `NULL` once used or never issued (org-creator signup) |
| `invite_token_expires_at` | datetime | yes | `NULL` alongside the hash |

`password_hash` stays `NOT NULL` until that same slice, since nothing creates a row
without one yet.

`employee.email`/`employee.employee_code`, `leave_type.code`, and
`public_holiday.holiday_date` each drop their global `unique = true` and move to a
class-level `@Table(uniqueConstraints = {...})` scoped to `(organization_id, <col>)`.

**`session` table/`SessionPojo`/`SessionService` are removed**, superseded by
stateless JWT -- no server-side session store. Flagged here so it isn't rebuilt as dead
code during implementation.

**`UserPrincipal`** (not yet modified) -- add `organizationId` (`Long`), populated by
`JwtAuthFilter` from the JWT claim, not by a DB lookup.

**`LoginForm`** (not yet modified) -- add `orgKey`.

## Database Indexes

Every tenant-scoped table's existing indexes gain `organization_id` as the leading
column, since every real query is tenant-scoped first -- e.g. `employee`'s `ix_emp_mgr`
becomes `(organization_id, manager_id)`, `leave_request`'s `ix_lreq_emp_status` becomes
`(organization_id, employee_id, status)`. The composite unique constraints on
`employee` (`organization_id, email` / `organization_id, employee_code`) already lead
with `organization_id`, so no separate `organization_id`-only index is needed there
(redundant per `jpa-persistence.md`'s rule against indexing what a unique constraint
already covers). Same reasoning applies to `leave_type.code` and
`public_holiday.holiday_date`'s scoped unique constraints. Exact per-table index names
get finalized in `prompts/schema.md` at implementation time.

## Data Access

Every `*Repo` method that currently takes a business key alone gains a leading
`organizationId` parameter -- e.g. `EmpRepo.findByEmail(String)` →
`findByOrganizationIdAndEmail(Long, String)`, and the same pattern for
`LeaveTypeRepo.findByCode`, `PublicHolidayRepo.findByHolidayDate`/`findByYear`.
`OrgRepo.findByOrgKey(String) -> Optional<OrgPojo>` (implemented).

### Migrations

Same shape repeated per table (pre-launch, so backfill-in-place rather than a
multi-release nullable rollout): add `organization_id` nullable → backfill to the
seeded default org's id → `ALTER ... NOT NULL` → add FK → drop any global unique
constraint on that table → add the scoped composite unique/index. `V12` created
`organization` (implemented); `V13` dropped `force_password_reset` (implemented); one
migration per remaining existing table (`employee`, `leave_type`,
`leave_type_approval_step`, `leave_request`, `leave_request_approval`,
`leave_ledger_entry`, `public_holiday`, `notification`, `audit_log`,
`exit_settlement`) still needs `organization_id` added, plus one adding
`invite_token_hash`/`invite_token_expires_at` to `employee` when that slice lands.
Exact `V<n>__*.sql` numbering is decided at implementation time against whatever the
latest migration is then.

## Configuration Parameters

- **Removed**: `app.org.name` (organization name now lives in the `organization`
  table). `app.session.durationDays` and any other session-cookie-specific config tied
  to the removed `session` table (the JWT cookie still needs a `maxAge`/expiry config,
  see `app.jwt.expiry-minutes` below).
- **New** (not yet added): `app.jwt.secret` (env var, HS256 signing key, never
  committed). `app.jwt.expiry-minutes` (default `60`).

## External Libraries

- `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` (not yet added) --
  signs/parses the JWT. Chosen over Spring Security's OAuth2 resource-server stack
  because this app issues its own tokens rather than validating an external identity
  provider's.

## Relevant Files

New (not yet built, this pass):
- `service/JwtService.java`
- `security/JwtAuthFilter.java` (replaces `UserAuthFilter`)
- `rest/AuthController.java` -- add `signup`, `setPassword`
- Employee-creation controller/flow (naming TBD) for `HR`/`ORG_ADMIN` creation +
  CSV import
- `src/main/resources/db/migration/` -- one migration per remaining tenant-owned table
  adding `organization_id`, one adding `invite_token_hash`/`invite_token_expires_at`

Modified (not yet built, this pass):
- `pojo/EmpPojo.java` and every other tenant-owned `*Pojo` -- add
  `organization_id`, scope any previously-global unique constraint
- `repo/EmpRepo.java` and every other tenant-owned `*Repo` -- org-scoped finder
  methods
- `service/EmpService.java` -- `verifyLogin` gains `organizationId`; new
  `createOrgAdmin`, `createHr`/`createEmployee`
- `workflow/AuthFlow.java` -- new `signup`; `login` gains `orgKey`
- `workflow/EmpFlow.java` -- `organizationName` sourced from `OrgService`
- `model/form/LoginForm.java` -- add `orgKey`
- `security/UserPrincipal.java` -- add `organizationId`
- `config/SecurityConfig.java` -- register `JwtAuthFilter` in place of `UserAuthFilter`
- `prompts/schema.md` -- add `organization_id` to every remaining tenant-owned table;
  remove `session`
- `prompts/libraries.md` -- add the `jjwt-*` artifacts
- `prompts/features/employee-auth-and-profile.md` -- changelog note pointing here

Already done (this doc's Progress section has the detail):
- `pojo/OrgPojo.java`, `repo/OrgRepo.java`,
  `service/OrgService.java`, `V12__create_organization.sql`,
  `V13__drop_force_password_reset.sql`, `exception/ConflictException.java`
- `pojo/EmpPojo.java`, `model/data/SessionData.java`,
  `service/EmpService.java`, `workflow/EmpFlow.java` -- `force_password_reset`
  removed
- `security/TenantContext.java` -- kept; `security/TenantResolutionFilter.java` --
  deleted (built, then found wrong, then removed same day)

Removed (already done):
- `pojo/PlatformAdminPojo.java`, `repo/PlatformAdminRepo.java`,
  `service/PlatformAdminService.java`, their migration and tests -- built, then deleted
  per the user's explicit decision (see Correction history)
- `security/TenantResolutionFilter.java` and its test

Removed (planned, not yet done):
- `pojo/SessionPojo.java`, `repo/SessionRepo.java`, `service/SessionService.java`, the
  `session` table (a migration to drop it, not a hand-edit)
