# Schema

Managed by Flyway (`src/main/resources/db/migration`), not `hbm2ddl.auto=update` --
every table below has a corresponding `V<n>__*.sql` migration. `updated_at` on mutable
entities is maintained by a `@PreUpdate` hook on the entity, not application code, so it
can't be forgotten on a new write path.

**Timestamps are UTC.** Every datetime column stores UTC. Entities use
`java.time.Instant` (a zone-free instant, never `LocalDateTime`); Hibernate is pinned
to `hibernate.jdbc.time_zone=UTC` (`DBConfig`, `app.properties`/`test.properties`) so
the `Instant` <-> `DATETIME` conversion normalizes through UTC regardless of the
server's default zone. Date-only columns stay `LocalDate` (calendar dates, no zone).

**2026-10-09 -- entity review fixes.** The current policy is `hbm2ddl.auto=update`, no
Flyway (see `backend/CLAUDE.md`). Changes `update` can't make (renames, enum value lists,
NOT NULL tightening, dropped columns, backfills) for an existing database are in
`docs/sql/2026-10-09-entity-review-fixes.sql`. Index names follow `idx_<table>_<cols>`.

## session

Tracks active login sessions. Source of truth for whether a session token is valid --
see `SessionService.validate`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| token | varchar | no | unique; the value sent in the `HttpOnly` session cookie |
| employee_id | bigint | no | plain FK column, no `@ManyToOne` mapping -- see `employee` below |
| created_at | timestamp | no | set on insert |
| expires_at | timestamp | no | set at creation from `app.session.durationDays` |
| revoked_at | timestamp | yes | `null` = active; set on logout or superseded by a new login (single-session policy) |

A session is active when `revokedAt == null && expiresAt > now`.

## organization

Tenant table -- see `prompts/features/multi-tenancy.md` for the full design. Tenant is
resolved **once, at login/signup**, from a user-typed `orgKey` field -- never from a
subdomain or per-request URL parsing (an earlier version of this doc briefly had a
`subdomain` column and a per-request resolution filter; both were wrong and are gone --
see that doc's Correction history). This table and its service/repo are the first slice
implemented; the rest (JWT, `organization_id` on `employee`/other tables) are separate,
later slices of the same feature. There is no `platform_admin` table in this design --
built, then deleted; see the same Correction history.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| name | varchar | **yes** | display name -- not collected at signup (less friction); `NULL` until set later via settings (not yet built) |
| org_key | varchar | no | unique (global) -- typed at login/signup, resolves the tenant |
| hr_notify_email | varchar | no | per-tenant HR cc address; set at creation, no default computed in code |
| country | varchar(2) | no (2026-10-09) | ISO 3166-1 alpha-2, upper-cased -- collected at signup (dropdown, required there) via `AuthFlow.requireCountry`, the only path that creates an org |
| status | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `OrgStatus`: `ACTIVE`, `SUSPENDED` |
| is_active | boolean | no, default true | separate soft-delete flag, kept alongside `status` per explicit user choice despite the two being independently settable (e.g. `is_active=true, status=SUSPENDED` is reachable) -- not in `leave-frontend/Prompts/multi-tenancy.md`'s spec, added here |
| created_at | timestamp | no | set on insert |
| updated_at | timestamp | no | `@PreUpdate` (2026-10-09: added -- name/country/status change after insert) |

`name` is `varchar(128)`, `org_key` is `varchar(64)` (matches the
`^[a-z0-9_-]{1,64}$` validation regex below), `hr_notify_email` is `varchar(190)`.
`OrgService.create` takes `(name, orgKey, hrNotifyEmail, country)`; the 3-arg overload
that created an org without a country is gone (2026-10-09).

**Trap avoided:** Hibernate always sends every mapped column's Java value on `INSERT` --
the column's `DEFAULT 1` only applies to a raw SQL insert that omits the column, never to
JPA-driven inserts. `OrgService.create()` sets `active(true)` explicitly for
this reason; any future employee-creation code must do the same for `employee.is_active`
or every app-created row would silently be inactive.

`V12__create_organization.sql` seeds one default tenant row (`id=1`, `org_key=acme`)
for later migrations to backfill existing `employee`/`leave_type`/`public_holiday` rows
against, and bumps the `organization_id` id-generator row to `2` so the app's own
table-generator allocations never collide with that manually-inserted id.

`OrgService.create(name, orgKey, hrNotifyEmail)` validates `orgKey`
against `^[a-z0-9_-]{1,64}$` (`BadRequestException` on mismatch -- hyphen *and*
underscore both allowed, a deliberate widening of the frontend doc's underscore-only
regex) and uniqueness (`ConflictException` -- maps to `409` in `GlobalExceptionHandler`,
first use of that status in this codebase). Not yet called from any controller -- the
self-service signup endpoint that calls it is a later slice.

## account

**The identity / authentication / RBAC table** (entity `UserPojo`). One row per account,
every role (`ORG_ADMIN`, `HR`, `EMPLOYEE`). The role-specific profile is a separate 1:1
detail table selected in code from `role` -- today that is always `employee` (below);
`admin`/`hr` is the same pattern, deferred until it has a column to hold. **2026-10-09:**
renamed from `user` -- a SQL keyword that had to be backtick-quoted everywhere; `account`
needs no quoting (jpa-persistence.md keyword rule). Holds auth state only: employment
state moved to `employee.status`. Migration `V21__create_user.sql`;
`V22__split_user_from_employee.sql` moved these columns out of the old combined
`employee` table.

`role` is the only RBAC input: `JwtService.issue` writes it (`.name()`) as the JWT
`role` claim, `JwtService.parseOrNull` copies it as the single entry in
`UserPrincipal.roles`. A user is created either by signup (`role = ORG_ADMIN`, `AuthFlow.signup` ->
`UserService.createUser`, `ACTIVE`) or by an admin invite / bulk import (`HrEmpFlow.persistRow`, role
fixed at invite time, `INVITED`), so no role-less row ever exists. The onboarding lifecycle is the
`status` column below. **2026-09-30:** `directory_profile_complete` (the old one-shot onboarding
flag) is no longer mapped -- an existing database must drop it by hand or inserts fail (`NOT NULL`, no
default); `password_hash` is now nullable.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| organization_id | bigint | no | plain FK column; DB-level FK -> `organization.id` (`fk_user_org`) |
| email | varchar(189) | no | unique per org (`uk_account_org_email (organization_id, email)`) -- login identity. 189, not 190 (2026-10-09): 8 (org id) + 189 x 4 = 764 bytes keeps the unique key under MySQL's 767-byte limit; 190 was 768. `FieldRules.email` caps input at 189 |
| password_hash | varchar(60) | **yes** (2026-09-30) | BCrypt output is always exactly 60 characters. `NULL` while `INVITED` -- the user sets it from the invite link; set only by the user (invite or forgot-password mail), never by an admin |
| email_verified | boolean | no, default false | set true when the user completes a set-password link (proof of inbox ownership; also by a forgot-password reset on an `INVITED` user). Also unset for the self-signup `ORG_ADMIN` -- an open gap, see `multi-tenancy.md` |
| role | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `UserRole`: `ORG_ADMIN` (signup), `HR` / `EMPLOYEE` (written by `POST /api/admin/users` and the bulk import, decided at invite time). No `PENDING` role -- `PENDING` is a *status* |
| status | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `UserStatus` (account state only, 2026-10-09): `INVITED` (no password yet) -> `PENDING` (password set; onboarding-only session) -> `ACTIVE` (onboarding form submitted, `UserService.activate`); `DISABLED` (set when HR exits the employee). `INVITED` and `DISABLED` block login (`CredentialService.verifyLogin`); `DISABLED` also kills any session (`UserService.findIfSessionValid`). The old `ONBOARDED`/`ON_NOTICE`/`EXITED` values moved to `employee.status`; old -> new: `ONBOARDED`/`ON_NOTICE` -> `ACTIVE`, `EXITED` -> `DISABLED`. Lifecycle in `prompts/features/employee-onboarding.md` |
| is_active | boolean | no | separate soft-delete flag, kept alongside `status` per explicit user choice (see `organization.is_active`) |
| failed_attempts | int | no, default 0 | account-lockout counter; reset to 0 on successful login |
| locked_until | timestamp | yes | set to `now + app.login.lockout-minutes` after `app.login.max-failed-attempts` consecutive bad passwords, checked before the password comparison on the next attempt |
| created_at | timestamp | no | set on insert |
| updated_at | timestamp | no | `@PreUpdate` |

No index on `status` -- `ix_user_status` was dropped (`V29`): a 3-value column with
no query filtering on it alone (`UserRepo` has no such method) was pure write cost.

**Transaction note on `failed_attempts`/`locked_until`:** written by
`CredentialService.recFailed`, which runs in its own `REQUIRES_NEW` transaction (via a
self-injected proxy) so the counter increment survives even though the caller
(`verifyLogin`) always throws and rolls back its own transaction right after. Without
this, lockout would silently never trigger.

## employee

Role-specific profile for a user who is an employee. **1:1 with `account`: the PK
*is* the account id** (`user_id`, no separate generator), assigned by the caller
before save -- a documented `backend/CLAUDE.md` exception to the `@TableGenerator`
rule, shared by `employee_sensitive` and `employee_photo`. `HR` / `ORG_ADMIN` users get an
`employee` row too -- they take leave, accrue, have a manager -- so every account
currently has one. Migration `V22` reshaped this table (dropped the auth columns, `id` ->
`user_id`).

| Column | Type | Nullable | Notes |
|---|---|---|---|
| user_id | bigint | no | PK; DB-level FK -> `account.id` (`fk_employee_user`) |
| organization_id | bigint | no | plain FK column; DB-level FK -> `organization.id` (`fk_emp_org`) |
| employee_code | varchar(64) | **yes** (2026-10-09) | unique per org when set (`uk_employee_org_code (organization_id, employee_code)`; multiple `NULL`s coexist). `NULL` until HR assigns one -- the old `ADMIN-xxxx`/`EMP-xxxx` placeholders are gone |
| name | varchar(128) | **yes** | not collected at signup (less friction); `NULL` until set later via settings |
| phone | varchar(20) | yes | |
| address | varchar(255) | yes | `API-REQUIREMENTS.md`'s session object and `PATCH /employees/me` both need it |
| date_of_joining | date | **yes** (2026-10-09) | `NULL` until HR sets it (no more `LocalDate.now()` placeholder). `LeaveBalanceService.ensureAnnualAccrual` accrues nothing while it is null |
| date_of_exit | date | yes | |
| status | varchar(16) | no | added 2026-10-09. `@Enumerated(EnumType.STRING)` -> `EmpStatus` (employment state): `ONBOARDING` (invited, form not submitted) -> `ONBOARDED` (form submitted) -> `ACTIVE` (first login after, `AuthFlow.login`) -> `ON_NOTICE` -> `EXITED` (HR exit; also sets `account.status = DISABLED`). Backfilled from the old `user.status`: `INVITED`/`PENDING` -> `ONBOARDING`, others same name. The API still shows one status (`HrEmpMapper.statusOf`: account value while `INVITED`/`PENDING`, else this) |
| employee_type | varchar(32) | no | `REGULAR`, `INTERN`, or any org-defined value -- plain string by design, not an enum: matched case-insensitively against `leave_type.applicable_employee_type`, so a new employee type is addable as data with no code deploy |
| designation | varchar(128) | yes | |
| department | varchar(128) | yes | |
| manager_id | bigint | yes | plain FK column referencing an `account.id`, no `@ManyToOne`, no DB-level FK constraint (per `02-database-schema.md`'s constraint policy) |
| basic_salary | decimal(12,2) | yes | |
| created_at | timestamp | no | set on insert |
| updated_at | timestamp | no | `@PreUpdate` |

Index: `manager_id` (`idx_employee_manager`, renamed from `ix_emp_mgr` 2026-10-09). The
profile photo is no longer here -- see `employee_photo`. `EmpRepo.searchDirectory` now
filters on `employee.status <> EXITED` directly (no join to `account`).

## employee_photo

Added 2026-10-09: the self-service profile photo, moved off `employee` so every
`findByOrgId`/`findByManagerId` read of `EmpPojo` stops loading a `MEDIUMBLOB`. 1:1 with
`account`, PK = account id (same pattern as `employee`). A row exists only while a photo is
set -- clearing deletes it -- so both payload columns are required. `hasPhoto` is an exists
query (`EmpPhotoRepo.existsByUserId`), never a blob load. Entity `EmpPhotoPojo`, service
`EmpPhotoService`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| user_id | bigint | no | PK |
| bytes | mediumblob | no | validated by magic-byte sniff (JPEG/PNG/WebP) and `app.photo.max-bytes` |
| content_type | varchar(20) | no | the sniffed type, not the client's claim |
| created_at | timestamp | no | set on insert |
| updated_at | timestamp | no | `@PreUpdate`; also set on replace |

## employee_sensitive

Directory Steps 4/5/6/9 (Personal, Address, Pay & IDs, Voluntary/EEO) -- the P2-P4
"sensitive module", see `prompts/features/employee-directory.md`, "Sensitive Module
(Steps 4/5/6/9)". Separate table from `employee`, not more columns there: every existing
`empSvc.findByUserId`/`findByOrg`/`findByManager` call (leave flows, manager views, HR
list) already reads whatever's on `EmpPojo`, which is fine for a job title but not
for a bank account number or an EEO answer -- same blast-radius reasoning as keeping the
auth entity narrow, applied to the highest-sensitivity employee data instead of
credentials. **1:1 with `employee`/`user`: the PK *is* the `user` id** (`user_id`, no
separate generator), assigned by the caller before save, same pattern as `employee`
itself. Added via `hbm2ddl.auto=update`, no migration -- every column below is nullable.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| user_id | bigint | no | PK; DB-level FK -> `employee.user_id` |
| date_of_birth | date | yes | |
| gender | varchar(50) | yes | free text by design, not an enum -- "a fixed list cannot represent everyone" |
| pronouns | varchar(40) | yes | free text, same reasoning |
| nationality | varchar(2) | yes | ISO 3166-1 alpha-2, validated via `Locale.getISOCountries()` in `DirectoryFlow` |
| marital_status | varchar(40) | yes | free text |
| blood_group | varchar(8) | yes | `@Enumerated(EnumType.STRING)` -> `BloodGroup` (`A_POS`..`O_NEG`) since 2026-10-09 -- a fixed value set is an enum. The API keeps the `"A+"` labels, converted at the Form/Data boundary (`BloodGroup.fromLabel`/`labelOf`) |
| personal_email | varchar(190) | yes | |
| languages | varchar(255) | yes | comma-separated free text, same convention as `employee.skills` |
| hobbies | varchar(255) | yes | |
| flat_no, address1, address2, city, landmark, postal_code, state, country | varchar | yes | `@Embeddable AddressPojo` (spec §2a). `address1`/`city`/`country` required together once any address field is filled (Java-side only, in `DirectoryFlow`); `postal_code`/`state` conditionally required for `US`/`CA`/`IN`/`GB`/`AU`/`BR`. `country` is ISO 3166-1 alpha-2. Legacy free-text `employee.address` is untouched and stays read-only |
| emergency_name, emergency_relationship, emergency_phone | varchar | yes | all three required together or none (`DirectoryFlow`) |
| bank_account | varchar(34) | yes | plain text, not encrypted at rest (CLAUDE.md exception). 34 = IBAN maximum, matching `FieldRules.bankAccount` (2026-10-09, was 255). Always masked to the last 4 characters in `DirectoryData` -- no reveal endpoint exists |
| national_id, tax_id, passport | varchar(30) | yes | same handling as `bank_account`; 30 = `FieldRules.idCode`'s ceiling (2026-10-09, was 255) |
| bank_ifsc | varchar(20) | yes | plain -- a routing code, not a secret |
| visa_status | varchar(100) | yes | plain |
| ethnicity, veteran_status, disability_status, religion, union_membership | varchar(100) | yes | voluntary EEO self-ID. `union_membership` is GDPR Art. 9 special category |
| health_notes | text | yes | |
| created_at | timestamp | no | set on insert |
| updated_at | timestamp | no | `@PreUpdate` |

No index beyond the PK -- nothing filters, joins, or sorts on any column here.
`DirectoryFlow` merges this table into a directory row only for an `ORG_ADMIN` or `HR` caller
(2026-09-30: HR added), masked to the last 4 characters for the number fields.

**Updated 2026-09-30 -- onboarding.** The row is created empty when a user is invited and is
filled by the user's onboarding form (`OnboardingFlow`: personal details + address, emergency
contact, pay and IDs) and afterwards edited by HR/admin (`EmpDetailsFlow`). Required-ness and
input rules are enforced in Java (`OnboardingSupport`, `FieldRules`), never as `NOT NULL` --
every column stays nullable. The address/emergency "together or none" and country-conditional
rules in the column notes above belonged to the removed `DirectoryFlow` write path; onboarding
instead makes address1/city/state/postal_code/country and all three emergency fields mandatory.

**Doc drift:** the table above lists columns (`pronouns`, `languages`, `hobbies`, `flat_no`,
`visa_status`, the EEO fields, ...) that `EmpSensitivePojo` does not currently map -- the entity
is the source of truth: phone, contact_address, date_of_birth, gender, nationality,
marital_status, blood_group, personal_email, the address embeddable, emergency_name/
relationship/phone, bank_account, bank_ifsc, national_id, tax_id, passport.

## admin / hr (deferred, not built)

Same shape as `employee` -- a 1:1 detail table keyed by `user_id` (PK) with a DB-level
FK to `account.id` -- for metadata that only applies to an elevated role and has no
place on `employee`. **Not created:** no such column exists yet. When one does, add
the table and a `role` -> detail-table switch in `EmpFlow.toData` (the single
current call site). An `HR` keeps its `employee` row regardless; the `admin`/`hr`
row is additive, not a replacement.

## Leave domain tables

Built out (see `backend/CLAUDE.md`'s "What's built so far"). Mirrors
`leave-frontend/Prompts/02-database-schema.md`, minus the `organization` table
itself (`organization_id` now exists on `leave_type` -- see that section --  it was
missing when this doc was first written and got added once a cross-org write hole
was found: any org's HR admin could mutate another org's leave types by id). A
closed, code-verified value set is `@Enumerated(EnumType.STRING)` -> a `pojo` enum
(`LeaveStatus`, `ApprovalAction`, `ApproverRole`, `LedgerEntryType`,
`AccrualStrategy`, `SituationType`); an open-ended or config-driven one (nothing
enforces a fixed list, or new values are meant to be addable as data) stays a plain
`String` -- `employee_type`/`applicable_employee_type`. (`notification.type`/
`related_entity_type`, `audit_log.entity_type`, and since 2026-10-09 `audit_log.action`
are enums too.)
No `*Pojo` declares a JPA relationship (`@ManyToOne` etc.) -- every "FK" below is a
DB-level constraint only, added in the `V<n>__*.sql` migration and enforced there,
invisible to Hibernate. No FK constraint anywhere uses `ON DELETE CASCADE` --
default `RESTRICT`.

### leave_type

Seed/reference data -- the single source for policy numbers (accrual rates, carry-forward
caps, etc.). HR-configured, not user-generated.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| organization_id | bigint | no | plain FK column; DB-level FK -> `organization.id` (`fk_leave_type_org`, `V27`). Every read and write is scoped through this -- `LeaveTypeService`'s six public methods all take `orgId`, `LeaveTypeRepo` exposes only org-scoped finders (no unscoped `findByCode`/`findByActiveTrue`), and `HrLeaveController` sources it from `requireOrgId()` (the JWT), never a path or body field |
| code | varchar(64) | no | unique per org (`uk_leave_type_org_code (organization_id, code)`, `V27` -- was a global unique key before the org-scoping fix); `CL`, `PL`, `MARRIAGE`, `MATERNITY`, `PATERNITY`, `SABBATICAL`, `LOP`, `INTERN_STUDY`, `PUBLIC_HOLIDAY`, `OPTIONAL_HOLIDAY` |
| name | varchar(128) | no | display name |
| applicable_employee_type | varchar(32) | **yes** (2026-10-09) | `NULL` = applies to every employee (replaces the old `'ALL'` sentinel; `LeaveTypeService.create` leaves it null); otherwise `REGULAR`, `INTERN`, or any org-defined type -- plain string, same reasoning as `employee.employee_type`. Java field is `applicableType` (shortened from `applicableEmployeeType`; column name unchanged) |
| approver_role | varchar(32) | no (2026-10-09) | `@Enumerated(EnumType.STRING)` -> `ApproverRole`: `MANAGER` (the applicant's manager, set by `LeaveTypeService.create`) or `HR`. Was nullable with `NULL` meaning `MANAGER`; the single-approver replacement for `leave_type_approval_step` below |
| accrual_strategy | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `AccrualStrategy`: `ANNUAL_PRORATA`, `PERIODIC`, `EVENT_BASED`, `NONE`. Only `ANNUAL_PRORATA` is implemented (`LeaveTypeService.create`, `LeaveBalanceService`) |
| accrual_config | text | yes | `String` field -- was `columnDefinition = "JSON"`; switched to `TEXT` (`V32`) because MySQL's native `JSON` validates on write and H2's `JSON` type doesn't behave the same way under the test config, same reasoning as `leave_request.reason`/`hr_notes`. No serialize/deserialize helper built yet, shape varies by `accrual_strategy` |
| max_carry_forward_days | decimal(5,1) | yes | null = no carry-forward. Java field is `maxCarryDays` (column unchanged) |
| requires_advance_notice_days | int | yes | e.g. 30 for Marriage/Maternity/Paternity. Java field is `noticeDays` (column unchanged) |
| is_encashable | boolean | no | true for PL at exit |
| is_situation_based | boolean | no | true only for Maternity |
| is_active | boolean | no | soft-disable instead of deleting a type with history |
| created_at | timestamp | no | set on insert (`LeaveTypeService.create`) |
| updated_at | timestamp | no | `@PreUpdate` |

`created_at`/`updated_at` added in `V31` (backfilled to the migration's run time for
pre-existing rows -- there was no earlier equivalent timestamp to backfill from).
`V3__create_leave_type.sql`; `organization_id` and the composite unique key added in
`V27__leave_type_add_org_scope.sql`; VARCHAR lengths set in `V28`; `accrual_config`
moved to `TEXT` in `V32`.

### leave_type_approval_step

The configurable approval chain for a leave type -- one row per step.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| leave_type_id | bigint | no | plain FK column; DB-level FK -> `leave_type.id` |
| step_order | int | no | 1, 2, ... |
| approver_role | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `ApproverRole`: `MANAGER`, `HR` (distinct from `UserRole` -- `MANAGER` here means "the applicant's manager", not an account role) |
| is_mandatory | boolean | no | reserved for future optional-step cases; every step is mandatory today |
| created_at | timestamp | no | set on insert |

Unique constraint on (`leave_type_id`, `step_order`) -- `uk_ltas_type_step`.
`LeaveTypeApprovalStepRepo.findByLeaveTypeIdOrderByStepOrder` walks the chain in order.
`V4__create_leave_type_approval_step.sql`; `created_at` added in `V31`.

### leave_request

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| user_id | bigint | no | plain FK column; DB-level FK -> `account.id` (`fk_lreq_user`) -- the request belongs to a person, not to the `employee` profile row |
| leave_type_id | bigint | no | plain FK column; DB-level FK -> `leave_type.id` |
| start_date, end_date | date | no | |
| num_days | decimal(5,1) | no | half-day aware |
| reason | text | yes | |
| situation_type | varchar(32) | yes | maternity sub-case only. `@Convert`-mapped (`SituationTypeConverter`, not plain `@Enumerated`) -> `SituationType`: `UP_TO_2_CHILDREN`, `TWO_OR_MORE_CHILDREN` (stored as `2_OR_MORE_CHILDREN` -- a Java enum constant can't start with a digit, so this one value needs an explicit converter instead of `name()`), `ADOPTION_UNDER_3MO`, `COMMISSIONING_MOTHER`, `MISCARRIAGE`, `PREGNANCY_ILLNESS_EXTENSION` |
| status | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `LeaveStatus`: `APPLIED`, `PENDING_MANAGER`, `PENDING_HR`, `APPROVED`, `REJECTED`, `CANCELLED`, `LAPSED` (reserved for the future accrual/expiry job -- nothing sets it yet) |
| current_step_order | int | no | which approval step is pending. Java field is `stepOrder` (shortened; column unchanged) |
| is_hr_imposed | boolean | no | true for disciplinary LOP created directly by HR |
| hr_notes | text | yes | dispute/override notes |
| parent_request_id | bigint | yes | self-FK; links a miscarriage-triggered request back to the original maternity request |
| applied_at | datetime | no | set unconditionally at apply time (`LeaveFlow.apply`) -- every insert path sets it, so it was tightened from nullable |
| cancelled_at | datetime | yes | |
| created_at | timestamp | no | set on insert, same instant as `applied_at` |
| updated_at | timestamp | no | `@PreUpdate` |

`created_at`/`updated_at` and the `applied_at` non-null tightening added in `V31`
(pre-existing rows backfilled from `applied_at`, or the migration's run time when that
was also null -- via `NOW()`, which follows the DB session's time zone rather than UTC;
flagged as a live gap, not yet fixed). No index on (`user_id`/`leave_type_id`, `status`) alone -- `ix_lreq_user_status`
and `ix_lreq_type_status` were dropped (`V33`): `LeaveRequestRepo` had no caller for
`findByUserIdAndStatus`/`findByLeaveTypeIdAndStatus` (both repo methods were removed in
the same change), so both indexes were pure write cost with nothing reading through
them. Indexes: `idx_leave_request_user` (`user_id`, backs `findByUserId`) and
`idx_leave_request_leave_type` (`leave_type_id`, the plain-FK-column index), renamed from
`ix_lreq_*` 2026-10-09. `V5__create_leave_request.sql`; column/index/FK renamed to
`user_id` in `V23__point_leave_fks_at_user.sql`.

### leave_request_approval

One row per approval step instance for a request -- the audit trail of who acted at each
step.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| leave_request_id | bigint | no | plain FK column; DB-level FK -> `leave_request.id` |
| step_order | int | no | mirrors `leave_type_approval_step.step_order` at time of application |
| approver_role | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `ApproverRole`: `MANAGER`, `HR` |
| approver_user_id | bigint | yes | plain FK column; DB-level FK -> `account.id` (`fk_lra_approver`); filled in when acted on |
| action | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `ApprovalAction`: `PENDING`, `APPROVED`, `REJECTED` |
| comments | text | yes | |
| acted_at | datetime | yes | |
| created_at | timestamp | no | set on insert. No `updated_at`: the row's only mutation (`LeaveFlow.stampStep` sets `action`/`approver_user_id`/`comments`/`acted_at` together) is already dated by `acted_at` |

`V6__create_leave_request_approval.sql`; `approver_employee_id` -> `approver_user_id`
in `V23__point_leave_fks_at_user.sql`; `created_at` added in `V31`.

### leave_ledger_entry

Append-only. No mutable balance column anywhere -- current balance for
(user, leave_type, year) is `SUM(amount_days) WHERE user_id=? AND
leave_type_id=? AND leave_year=?`, backed by `LeaveLedgerEntryRepo
.findByUserIdAndLeaveTypeIdAndLeaveYear`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| user_id | bigint | no | plain FK column; DB-level FK -> `account.id` (`fk_lledg_user`) |
| leave_type_id | bigint | no | plain FK column; DB-level FK -> `leave_type.id` |
| leave_year | int | no | calendar year this entry belongs to |
| entry_type | varchar(32) | no | `@Enumerated(EnumType.STRING)` -> `LedgerEntryType`. Only `ACCRUAL` and `DEBIT` are implemented (`LeaveBalanceService`); `CARRY_FORWARD`/`ENCASHMENT`/`ADJUSTMENT`/`EXPIRY` from `02-database-schema.md` aren't in the enum yet -- add them there when a write path needs them |
| amount_days | decimal(5,1) | no | signed: positive for credit, negative for debit |
| effective_date | date | no | |
| reference_leave_request_id | bigint | yes | plain FK column; DB-level FK -> `leave_request.id`; set for DEBIT entries. Java field is `refRequestId` (shortened from `referenceLeaveRequestId`; column unchanged) |
| created_by | bigint | no | plain FK column; DB-level FK -> `account.id` (`fk_lledg_created_by`); the account whose action wrote the entry (the applicant for an accrual, the final approver for a debit). No system jobs write here yet |
| created_at | datetime | no | |

Index: `idx_leave_ledger_entry_user_type_year` (`user_id`, `leave_type_id`, `leave_year`;
renamed from `ix_lledg_user_type_year` 2026-10-09).
`V7__create_leave_ledger_entry.sql`; column/index/FKs renamed to `user_id` in
`V23__point_leave_fks_at_user.sql`.

### public_holiday

Weekly holidays (every Sunday) and optional/floating holidays are **not** here -- see
`02-database-schema.md`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| holiday_date | date | no | unique |
| name | varchar(128) | no | |
| created_at | timestamp | no | set on insert |

**2026-10-09:** `holiday_year` removed -- it claimed to speed year queries but the query used
`YEAR(holiday_date)` and never read it. A year's list is now a `holiday_date` range
(Jan 1 - Dec 31) on the `(org_id, holiday_date)` unique index
(`PublicHolidayRepo.findByOrgIdAndHolidayDateBetween`, ordered by date).
`V8__create_public_holiday.sql`; `created_at` added in `V31`.

### notification

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| user_id | bigint | no | plain FK column; DB-level FK -> `account.id` (`fk_notif_user`); recipient |
| type | varchar(32) | no | `@Enumerated(STRING)` -> `NotificationType`: `LEAVE_APPLIED`, `LEAVE_APPROVED`, `LEAVE_REJECTED`, `LEAVE_CANCELLED`, `ONBOARDING_COMPLETED` (2026-09-30: last added -- **add it to the MySQL `enum` by hand on an existing database**). This row was previously documented as an open-ended string; the entity is an enum |
| title | varchar(255) | no | |
| message | text | no | |
| related_entity_type | varchar(64) | yes | e.g. `LEAVE_REQUEST`. Java field is `relEntityType` (shortened from `relatedEntityType`; column unchanged) |
| related_entity_id | bigint | yes | e.g. 42 |
| is_read | boolean | no | Java field is `read` (Lombok primitive-boolean convention: `isRead()`/`setRead()`) |
| created_at | datetime | no | |
| updated_at | datetime | no | `@PreUpdate` (2026-10-09: added -- `is_read` flips after insert) |

Index: `idx_notification_user_created` (`user_id`, `created_at`) -- backs
`NotificationRepo`'s per-user, newest-first lists (2026-10-09).
`V9__create_notification.sql`; `employee_id` -> `user_id` in
`V23__point_leave_fks_at_user.sql`.

### audit_log

Hand-written from the service layer at sensitive mutation points only (HR override,
imposed LOP, exit finalize, leave-type config changes) -- not a generic entity-listener
setup.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| entity_type | varchar(32) | no | `@Enumerated(STRING)` -> `EntityType` (`ORGANIZATION`, `USER`, `EMPLOYEE`, `LEAVE_TYPE`, `LEAVE_REQUEST`, `PUBLIC_HOLIDAY`) -- what was changed. Written since 2026-09-30 by `AuditLogService.record` (only `EMPLOYEE_DETAILS_UPDATED` so far) |
| entity_id | bigint | no | |
| action | varchar(64) | no | `@Enumerated(STRING)` -> `AuditAction` (2026-10-09, was a free string): only `EMPLOYEE_DETAILS_UPDATED` (HR/admin edit of onboarding details); add a constant with each new `AuditLogService.record` caller. `after_json` for that action is `{"sections":[...]}` -- section names only, never values |
| performed_by | bigint | no | plain FK column; DB-level FK -> `account.id` (`fk_audit_performed_by`) -- column name unchanged, only the referenced table moved |
| before_json, after_json | text | yes | `String` fields -- were `columnDefinition = "JSON"`; switched to `TEXT` (`V32`), same reasoning as `leave_type.accrual_config`. Snapshot of the change |
| created_at | datetime | no | |

No index on (`entity_type`, `entity_id`) despite `AuditLogRepo.findByEntityTypeAndEntityId`
existing -- an `idx_audit_log_entity` index was added (`V30`) and then dropped again
(`V34`) once it was noticed nothing in `src/main` calls that method either; add the
index back alongside whatever feature finally writes this table.
`V10__create_audit_log.sql`; FK re-pointed to `user` in
`V23__point_leave_fks_at_user.sql`.

### exit_settlement

One settlement per exit.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| employee_id | bigint | no, unique | plain FK column; DB-level FK -> `employee.id` |
| exit_date | date | no | last working day |
| notice_date | date | no | when resignation/exit was initiated |
| pl_balance_at_exit | decimal(5,1) | no | snapshot from ledger at calculation time |
| pl_encashed_days | decimal(5,1) | no | capped at a configurable max -- see `open-questions.md #18` |
| pl_encashment_amount | decimal(12,2) | no | `pl_encashed_days * (basic_salary / 30)` -- see `leave-policy-engine.md` |
| excess_leave_days | decimal(5,1) | no | leave taken beyond pro-rata entitlement in the exit year |
| excess_deduction_amount | decimal(12,2) | no | deducted at last basic pay |
| partial_month_encashment_days | decimal(2,1) | no | 0.5 or 1, per the exit-day-of-month rule |
| basic_salary_used | decimal(12,2) | no | snapshot, since salary could change later |
| status | varchar | no | `DRAFT`, `FINALIZED` |
| computed_by | bigint | no | plain FK column; DB-level FK -> `employee.id` |
| computed_at | datetime | no | |

`V11__create_exit_settlement.sql`.

## Billing / subscription tables

See `prompts/features/billing-subscription.md` for the full feature. Unlike every
table above, these three were **not** added via a Flyway migration -- this project's
actual, current schema policy (see `backend/CLAUDE.md`) is `hibernate.hbm2ddl.auto=update`
with no migration tool; this doc's own Flyway framing at the top predates that policy
and is stale for anything added from here down.

### plan

Global reference/catalog data (not per-org) -- seeded once, on an empty table, by
`PlanService.seedDefaults` (a `@PostConstruct` check, not a migration).

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| code | varchar(16) | no | unique (global). `@Enumerated(STRING)` -> `PlanCode`: `FREE`, `STARTER`, `GROWTH`, `ENTERPRISE` (2026-10-09, was a `varchar(32)` string compared against `"FREE"` literals) |
| name | varchar(64) | no | display name |
| seat_limit | int | no | max non-`DISABLED` `account` rows the org may have on this plan (`UserRepo.countActiveByOrgId`); `ENTERPRISE` uses `Integer.MAX_VALUE` (never the seat-capping tier) |
| price_minor | bigint | no | flat price per billing period, minor units (paise); `0` for `FREE` and `ENTERPRISE` (the latter has no flat price to charge at all) |
| currency | varchar(3) | no | ISO 4217 |
| billing_period | varchar(16) | no | `@Enumerated(STRING)` -> `BillingPeriod`: `MONTHLY` (only value seeded), `ANNUAL` reserved |
| razorpay_plan_id | varchar(64) | yes | Razorpay's own Plan id; `null` for `FREE`/`ENTERPRISE` |
| is_active | boolean | no | soft-disable a tier without deleting history that references it |
| is_custom_pricing | boolean | no | `true` only for `ENTERPRISE` -- blocks self-serve `POST /api/billing/subscribe` (409) |
| created_at | timestamp | no | set on insert |

No index beyond the unique `code` -- a handful of rows, every read goes through `code`
or the PK.

### org_subscription

1:1 with `organization`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| org_id | bigint | no, unique | one row per org |
| plan_id | bigint | no | plain FK column -> `plan.id`; live-joined, not snapshotted -- a later edit to a plan's `seat_limit` applies to existing subscribers immediately (deliberately different from an invoice-style point-in-time snapshot: `seat_limit` is an operational cap, not a financial/legal figure) |
| status | varchar(16) | no | `@Enumerated(STRING)` -> `SubscriptionStatus`: `PENDING`, `ACTIVE`, `GRACE`, `CANCELLED` (transient -- the same transaction that observes it downgrades to `FREE`/`ACTIVE`) |
| razorpay_subscription_id | varchar(64) | yes, unique | `null` while on `FREE`; MySQL allows multiple `NULL`s under a unique index |
| current_period_end | timestamp | yes | from the gateway; `null` on `FREE` |
| grace_until | timestamp | yes | set on the first `pending`/`halted` sighting; cleared on recovery or downgrade |
| created_at, updated_at | timestamp | no | `AuditedPojo` -- `updated_at` bumped on every transition |

Indexes: `unique(org_id)`, `unique(razorpay_subscription_id)`,
`idx_org_subscription_status_updated` (`status`, `updated_at`; backs `findReconcilable`)
and `idx_org_subscription_status_grace` (`status`, `grace_until`; backs
`findGraceExpired`) -- both added 2026-10-09 so the scheduler's batch polls don't scan and
sort the table. No checkout URL on the entity: `SubscriptionService.subscribe` returns it
in a `SubscribeResult` alongside the row (the old `@Transient checkoutUrl` is gone).

### billing_event

Diagnostics + webhook-idempotency log. The unique constraint on `razorpay_event_id` *is*
the idempotency mechanism (`SubscriptionService.insertEventIfNew`), never read to drive
behavior otherwise.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | bigint | no | `@TableGenerator`, allocationSize 100 |
| razorpay_event_id | varchar(64) | no, unique | Razorpay's own event id where the gateway supplies one; a SHA-256 hex hash of the raw payload otherwise (see `BillingWebhookController.eventId` -- **unverified against a live Razorpay payload**, see that class) |
| event_type | varchar(64) | no | e.g. `subscription.charged`; plain `String`, not an enum -- Razorpay's own open-ended event names |
| org_id | bigint | yes | resolved from the payload's subscription id where possible; `null` if unresolvable (logged, not fatal) |
| payload | text | yes | raw event JSON, diagnostics only |
| processed_at | timestamp | no | |

Index: `unique(razorpay_event_id)` only -- nothing reads this table by `org_id` yet.

## email_outbox

One queued outbound email (`EmailOutboxPojo`); `*Mailer` services enqueue, the
`EmailSender` scheduler sends. Columns: `id`, `type` (`EmailType`), `user_id` (nullable,
log correlation only), `to_address`, `subject`, `plain_body`/`html_body` (text), `status`
(`EmailStatus`: `PENDING`, `SENT`, `FAILED`), `attempts`, `last_error`, `sent_at`,
`created_at`, and `updated_at` (`@PreUpdate`; 2026-10-09: added -- status/attempts change
after insert).

Index: `idx_email_outbox_status_created` (`status`, `created_at`) -- backs the sender's
`PENDING` poll and the reaper (2026-10-09). `scheduler.EmailOutboxReaper` deletes `SENT`/
`FAILED` rows older than `app.mail.reaper.retention-days` (default 30), in batches of
`app.mail.reaper.batch-size` (20) per transaction, hourly.
