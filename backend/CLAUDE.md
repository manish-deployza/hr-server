# leave-backend

## Stack
Spring backend (plain Spring Framework 6 + Spring Security 6, not Spring Boot)

## Overrides to the organization standards
- **No `deployza-parent-pom`.** This project is not part of the deployza org and has no
  access to its private Artifact Registry. All dependency versions are declared locally
  in `pom.xml` as `${artifactId.version}` properties instead of inheriting from the
  parent. `spring-backend-standards` (`coding.md` §13) otherwise applies as written.
- **Role/route gating is `@PreAuthorize` on each `*Controller` (`@EnableMethodSecurity` in
  `SecurityConfig`), not `authorizeHttpRequests` matchers.** `spring-backend-standards`
  (`architecture.md`, "Authorization Sits In Two Places") calls for role/route rules in the
  security chain; this backend puts them on the controller instead, for per-endpoint
  readability. `SecurityConfig` still owns the public/authenticated split (`permitAll` for
  signup/login/etc., `anyRequest().authenticated()` for everything else) -- only the
  role-vs-role distinction (`ORG_ADMIN`/`HR`/`EMPLOYEE`) moved to annotations. Resource-level
  (object) authorization still lives in the `*Flow`/controller helper methods, unchanged.
- **1:1 extension tables are keyed by the user id, with no `@TableGenerator`.** `employee`,
  `employee_sensitive` and `employee_photo` use `user_id` (the `account` row's id) as their
  primary key, assigned by the caller. `jpa-persistence.md` §2 asks every `*Pojo` for a
  table-generated id and has no rule for this case. A surrogate id would add a column and a
  unique index that every lookup ignores, and moving the live primary keys needs a hand
  migration. Revisit if the standard rules on it.
- **Session revocation is a per-user watermark (`account.tokens_valid_after`), not a
  per-token `jti` blocklist.** `security-standards` calls for checking a token's `jti`
  against a revocation list; this backend instead rejects any JWT whose `iat` predates the
  user's watermark (`UserService.findIfSessionValid`/`revokeSessions`). Deliberate simplification:
  no session table, no cleanup job for expired revocation rows. Known cost: revocation is
  all-or-nothing per user (logging out one device logs out every device; no way to revoke a
  single leaked token). `findIfSessionValid` truncates the watermark to whole seconds before
  comparing, matching the JWT `iat` claim's second-granularity, to avoid rejecting a
  same-second re-login after a revoke. Move to a `jti` + session-table model (tracked as a
  real feature, not a review-fix patch) if per-device logout or single-token revocation is
  ever needed.

## What this backend implements
The REST API described in `leave-frontend/fronted/API-REQUIREMENTS.md` -- leave
management (employee self-service, manager approvals, HR admin), one-on-ones, and
appraisals, behind cookie-based session authentication. That document is the API
contract; do not restate its shapes here.

## Deployment
WAR, deployed to an external Tomcat/servlet container. No embedded server, no `web.xml`
(replaced by `AppInitializer`, a `WebApplicationInitializer`).

## Configuration
- `app.properties` (prod) / `test.properties` (test, H2) -- see `DBConfig`.
- `db.password` in `app.properties` is intentionally blank -- override with the
  `DB_PASSWORD` environment variable in every real deployment; never commit a real value.
  Locally: `export DB_PASSWORD='Password@DB'` (or `-DDB_PASSWORD=...`) before running.
- Local dev DB: MySQL, schema `leave`, user `user-leaveManagement` (`GRANT ALL` on
  `leave`.* only). `hibernate.hbm2ddl.auto=update` -- Hibernate owns the schema directly
  from the `@Entity` mappings in `com.leaveapp.pojo`, no migration tool. No Flyway (this
  is a deliberate override to `02-database-schema.md`'s migration-tool guidance): schema
  changes are made by editing entities and letting Hibernate add what's missing on
  startup. Note this means anything not expressible in JPA annotations -- explicit FK
  constraints, indexes beyond `@Table(indexes=...)`, check constraints -- won't be
  created automatically; add those by hand if needed. Tests run H2 with
  `hbm2ddl.auto=create-drop` instead (`test.properties`). Changes `update` can't make
  (table renames, enum value changes, NULL -> NOT NULL, dropped columns, backfills) go in a
  dated, hand-run script under `docs/sql/`; run it against every existing database before
  starting the build that needs it. Latest: `docs/sql/2026-10-09-entity-review-fixes.sql`.
- The 4 masked directory fields (bank account, national ID, tax ID, passport -- see
  `prompts/features/employee-directory.md`, "Sensitive Module") are stored as plain
  strings, not encrypted at rest. Masking to the last 4 characters happens only in the
  `/api/directory` response (`DirectoryFlow.maskTail`), never in the database.

## What's built so far
Cross-cutting scaffold (config classes, request/logging filters, cookie-session auth
filter, global exception handling) plus one implemented feature:
employee auth and profile (`prompts/features/employee-auth-and-profile.md`) -- login,
session `/me`, logout, and self-service profile GET/PATCH, backed by a real `EmpPojo`.
(`change-password` was removed 2026-09-17 -- password changes go through the
forgot-password flow instead, see `prompts/api-endpoints.md`.) Plus
`GET` / `PATCH` / `DELETE /api/hr/employees[/{id}]`
(`prompts/features/hr-employee-management.md`) -- `ORG_ADMIN`/`HR` list every
account in their org (`EmpRowData[]`, unpaged), edit one by id (PATCH semantics, admin
password reset, `status` write), and soft-delete one (employee `status=EXITED` +
`date_of_exit=today` + account `DISABLED`, idempotent, no hard delete). Only an `ORG_ADMIN`
creates users: `POST /api/admin/users` (name + email + role) creates an `INVITED` account and
mails an invite; `POST /api/admin/users/{id}/resend-invite` re-sends it. Status is two
columns: `account.status` (auth only: `INVITED` -> `PENDING` (password set; session limited
to `/api/onboarding/**` via `ROLE_ONBOARDING`) -> `ACTIVE` (form submitted); `DISABLED`
blocks login) and `employee.status` (employment: `ONBOARDING` -> `ONBOARDED` (form
submitted) -> `ACTIVE` (first login after) -> `ON_NOTICE` -> `EXITED`). The API still shows
one `status` (`HrEmpMapper.statusOf`: the account value while `INVITED`/`PENDING`, the
employee value after).
`POST /api/hr/employees/import` (`ORG_ADMIN` only) bulk-invites from parsed CSV/TSV rows:
`name`, `email` and `role` are mandatory per row (a row missing one is skipped with a
reason), every other column is optional; each created user is `INVITED` and gets the same
invite mail, and the response is just `created` + `skipped`.
`GET /api/directory` is read only (personal/address/pay details and salary go to
`ORG_ADMIN` and `HR`, masked to the last 4 characters where sensitive).
The invited user's form is `/api/onboarding` (`ROLE_ONBOARDING` only): `GET` (saved
entries + per-step completion + `nextStep` = the preview), `PUT` `/personal` (personal
details *and* the home address, flat in one body), `/emergency`, `/pay-ids` (each validates
its step; every field but blood group, address line 2, landmark and passport is mandatory),
and `POST /submit` (account `PENDING` -> `ACTIVE`, employee -> `ONBOARDED`, onboarding
session revoked, HR and org admins notified). Input is strict: `FieldRules` checks every field's length (matching the column),
shape and charset and answers a 400 naming the field, and the new request bodies reject
unknown JSON properties (so they are declared flat -- Jackson skips that check on
`@JsonUnwrapped`). An `ONBOARDING`/`ONBOARDED` employee's status can't be PATCHed by HR
except to `EXITED` (which also disables the account); a forgot-password reset on an
`INVITED` user moves them to `PENDING`.
`UserService` (identity) and `CredentialService` (login, hashing, set/reset password) split
the old single class to stay under the 200-line ceiling. After that `ORG_ADMIN`/`HR` edit
the details with `PATCH /api/hr/employees/{id}/details` (audited by section name, never by
value); reporting manager and employment fields stay on `PATCH /api/hr/employees/{id}`.
Leave balances are not created at onboarding: `LeaveBalanceService` accrues lazily from the
joining date HR sets, and accrues nothing while it is null (employee code and joining date
start null -- no placeholders). The profile photo lives in its own `employee_photo` table
(`EmpPhotoService`), not on `employee`. `scheduler.EmailOutboxReaper` deletes SENT/FAILED
`email_outbox` rows past `app.mail.reaper.retention-days`.
Existing MySQL databases need the new enum values added by hand (Hibernate maps string
enums to MySQL `enum(...)` and `update` never alters an existing column):
`notification.type` gets `ONBOARDING_COMPLETED`; the `account`/`employee` status values
are in `docs/sql/2026-10-09-entity-review-fixes.sql`. `hr_approved` and
`directory_profile_complete` are gone from the entity but may still exist as columns in an
existing database -- this project's schema policy never drops columns automatically.
`directory_profile_complete` is NOT NULL with no default, so drop it by hand
(`ALTER TABLE account DROP COLUMN directory_profile_complete`) or inserts fail.

Plus the **leave domain** (`prompts/api-endpoints.md` "Leave Domain"): employee
leave-type browse / balances / apply / list / cancel, the unified manager+HR
approve-reject action, manager team + team-requests views, HR company-wide requests
+ leave-type CRUD + holiday read. Balances come off an append-only ledger with a
naive pro-rata annual accrual (no quarterly cadence / carry-forward / accrual job
yet).

Plus **billing/subscription** (`prompts/features/billing-subscription.md`): every org
gets a seat-based `org_subscription` (one of `FREE`/`STARTER`/`GROWTH`/`ENTERPRISE`),
auto-created `FREE` at signup; `ORG_ADMIN` upgrades/downgrades via a recurring
Razorpay Subscription (`/api/billing/subscribe`), settled by
`POST /api/billing/webhook/razorpay` (signature-verified, idempotent) with a
reconcile-poll + grace-period-downgrade scheduler as the catch-all. `ENTERPRISE` is
custom-quoted, not self-serve (`409` on `subscribe`, assigned manually once terms are
agreed). New employee creation (`HrEmpFlow.create`, `EmpImportFlow.importEmployees`)
is gated on the org's current seat limit. `RazorpayGatewayService`'s real-SDK branch
is still a stub -- `razorpay.enabled=false` everywhere so far, and the
`razorpay-java` dependency's licence is not yet confirmed (see `prompts/libraries.md`).

One-on-ones, appraisals, and the rest of HR CRUD are still unbuilt -- those
are built one at a time via `/deployza-code:feature`.
