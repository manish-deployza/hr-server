# Employee Directory

> **Updated 2026-09-30 -- read this first; it overrides the sections below where they disagree.**
> The directory is now **read only**: `GET /api/directory` is the only endpoint left.
> - **Removed:** `POST`, `PATCH` and `DELETE /api/directory[/{id}]` (`DirectoryFlow.create/update/deactivate`,
>   `DirectoryForm`, the `ORG_ADMIN` write matchers). Users are created by `POST /api/admin/users`
>   (invite), edited by `PATCH /api/hr/employees/{id}` and `PATCH /api/hr/employees/{id}/details`
>   (personal, address, emergency contact, pay and IDs; audited), and soft-deleted by
>   `DELETE /api/hr/employees/{id}`.
> - **Removed:** the one-shot onboarding gate `POST /api/employees/me/directory-profile`
>   (`DirectoryFlow.completeMyProfile`) and the `user.directoryProfileComplete` flag (also gone from
>   `/api/auth/me` and the employee rows). Onboarding is now `/api/onboarding/**` and the
>   `user.status` lifecycle -- see `employee-onboarding.md`.
> - **Changed:** the sensitive block (`personalInfo`, `address`, `emergencyContact`, masked
>   `bankAccount`/`nationalId`/`taxId`/`passport`, `bankIfsc`) and `basicSalary` are returned to
>   `ORG_ADMIN` **and `HR`** (`DirectoryController.list`: `includeSensitive = ORG_ADMIN || HR`), no longer
>   ORG_ADMIN only. Managers and employees never receive them. Masking to the last 4 characters is
>   unchanged.
> - **Doc drift, code wins:** the "Sensitive Module" text below describes AES-GCM encryption with a
>   key and blind index, and a wider column set; the built `EmpSensitivePojo` stores these fields as
>   plain strings (per `backend/CLAUDE.md`) and has only the columns listed in `schema.md`.

## Status: Built — Access Control Model / API / Business Logic / Data Models sections

`GET/POST/PATCH/DELETE /api/directory` implemented exactly as specified in those sections:
`DirectoryScope` (`pojo`), `DirectoryData`/`DirectoryForm` (`model`), `DirectoryFlow`
(`workflow`), `DirectoryController` (`rest`), three `ORG_ADMIN`-only method matchers added
to `SecurityConfig`. 283/283 tests pass (13 new in `DirectoryFlowTest` covering each
scope, each filter alone and combined, pagination, and the write validations; 22 new in
`DirectoryControllerTest` covering scope resolution incl. the ORG_ADMIN-with-reports
ordering rule, the `managerId`-filter-is-admin-only 403, page/size clamping, the
`ORG_ADMIN` write gate, and the cross-org 404 / self-deactivate 400 object-level checks).
No deviation from the spec during implementation.

**Update:** Steps 3 (Org details), 7 (Growth) and 8 (Profile) — 16 more P0/P1 fields — were
added afterward: `workLocation`, `gradeBand`, `costCenter`, `shiftTimezone`,
`probationEndDate`, `skills`, `certifications`, `training`, `mentorId`, `education`,
`previousEmployer`, `badgeNumber`, `assetIds`, `licenses`, `linkedIn`, `bio`. Plain nullable
columns on `employee` (added via `hbm2ddl.auto=update`, no migration needed — none of them
are `NOT NULL`), `skills`/`certifications`/`training`/`assetIds`/`licenses` are
comma-separated free text (no tag/array infra), `mentorId` is self-referencing like
`managerId` (own `ix_emp_mentor` index, resolves-in-org + not-self validated the same way).
Directory-only by design: `EmpForm`/`HrEmpFlow`/`EmpRowData` are untouched,
so these fields are invisible to `/api/hr/employees` for now — same deliberate dual-path
situation as the write-authority split above. New `EmpService.applyDirectoryFields`
(sibling to `applyAdminFields`, kept separate for exactly that reason). 286/286 tests pass
(3 more in `DirectoryFlowTest`: Step 3/7/8 fields round-trip through create+list, unknown
`mentorId` on create is a 400, self-mentor on update is a 400, growth fields apply on
update).

**Status update — Steps 7 (Growth) and 8 (Profile), and the rest of Step 3, reverted.**
Only `workLocation` stays on `employee`. `gradeBand`, `costCenter`, `shiftTimezone`,
`probationEndDate`, `skills`, `certifications`, `training`, `mentorId`, `education`,
`previousEmployer`, `badgeNumber`, `assetIds`, `licenses`, `linkedIn`, `bio` are dropped
from `EmpPojo`, `DirectoryForm`, `DirectoryData`, `EmpService.applyDirectoryFields`, and
`DirectoryFlow` (including the `mentorId` self/resolves-in-org validation and the
`ix_emp_mentor` index). `hbm2ddl.auto=update` never drops columns, so any dev/staging DB
that already ran with these fields keeps the now-unmapped columns until manually dropped.

**Status update — Steps 4, 5, 6, 9 are now PLANNED for a scoped build** (not the full
27-day Phase 4 programme). Previously declined for good reasons — no encryption
infrastructure, no migration path for the structured address, Step 9's EEO isolation
being unenforceable, `basicSalary` staying HR-only being deliberate — this pass reverses
that decision on explicit instruction, with each blocker resolved narrowly rather than by
building the whole compliance programme:

- **Encryption**: built now, JDK `javax.crypto` (AES-256-GCM), not deferred to Phase 4's
  KMS/HSM plan. See "Sensitive Module" below.
- **Address migration**: was never actually blocked — the doc's own §2a already resolved
  it (nullable new columns, legacy `employee.address` untouched). The "needs Flyway"
  language in the Hard Blockers section refers to the *full* programme's backfill/enum
  work, not to adding nullable columns, which this project already does routinely
  (`hbm2ddl.auto=update`, same as Steps 3/7/8).
- **Step 9 EEO isolation**: still not enforceable (no `EEO_REPORTING` role). Shipping
  anyway, gated to `ORG_ADMIN` like every other sensitive step. **The gap is explicit,
  not silently dropped**: an `ORG_ADMIN` (who is also comp authority) can see these
  answers. Recorded here so it is never later reported as fixed.
- **`basicSalary`**: this pass explicitly reverses the prior decision and adds it to
  `/api/directory`'s write surface. **This means `ORG_ADMIN` gains a second write path to
  salary**, alongside `HR`'s existing one via `/api/hr/employees`. Called out
  because it directly reverses language earlier in this doc calling the exclusion
  "deliberate, not a gap."

See "Sensitive Module (Steps 4/5/6/9)" below for the full design. The Compliance
Programme section further down still describes the *full* v2.0 spec (Phases 0–6) and
remains planned-not-scheduled beyond what is built here.

An org-scoped employee directory with a tiered read model and a single `ORG_ADMIN`-only
write surface at `/api/directory`.

**Write authority.** `ORG_ADMIN` is the only role that may create, edit or deactivate a
directory record. `HR` has **no** write access to the directory. An employee may
write their own profile only, through the existing `PATCH /api/employees/me` (`phone`,
`address`) — unchanged by this feature.

**Read authority.** Tiered by what the caller is, resolved server-side from the JWT and
the manager graph, never from a request parameter:

| Caller | Scope | Sees |
|---|---|---|
| `ORG_ADMIN`, `HR` | `ORG` | every non-exited account in their own org |
| has ≥1 direct report (derived, no `MANAGER` role exists) | `REPORTS` | their direct reports only |
| plain `EMPLOYEE` | `SELF` | their own row only |

Only P0/P1 fields cross the wire on this endpoint, in either direction. P2–P4 fields
(date of birth, home address, personal email, national ID, bank details, salary,
background checks, visa status, EEO self-ID) are never readable or writable here — see
Field Sensitivity Classification.

**Relationship to the existing HR surface.** `GET/POST/PATCH/DELETE /api/hr/employees`
stays exactly as it is for now, `ORG_ADMIN`/`HR`, full field set including
`basicSalary`. That means two write paths over the same two tables with different rules
for the duration. **This is deliberate and temporary**, not a bug: narrowing or retiring
`HrEmpController` is its own change with its own planning pass, because it touches
17 existing controller tests and the bulk-import flow. Until then, an `HR` can
still write via `/api/hr/employees` — so the "HR can only read" rule is fully in force
only after that follow-up ships. Recorded here so nobody reads the gap as an oversight.

## Access Control Model

### Read

`GET /api/directory` — any authenticated session. No `requireAnyRole` gate; the **scope**
is what varies, not the right to call it.

Scope resolution, in `DirectoryController.resolveScope(userId)`, first match wins:

1. `hasRole(ORG_ADMIN) || hasRole(HR)` → `ORG`
2. `empSvc.existsManager(userId)` → `REPORTS`
3. otherwise → `SELF`

Order matters: an `ORG_ADMIN` who also has direct reports gets `ORG`, not `REPORTS`.
`existsManager` already exists (`service/EmpService.java:53`) and already backs
`SessionData.isManager` — reused, not reinvented. There is no `MANAGER` role in
`UserRole`; "manager" is derived from `employee.manager_id`, exactly as
`ManagerLeaveFlow`/`GET /api/manager/team` and `LeaveController.requireCanAct` already do.

Org scope comes from `requireOrgId()` (the JWT) in every scope, including `REPORTS`.
`EmpRepo.findByManagerId` is **not** org-scoped, so `DirectoryFlow` always builds the
org's user map from `userSvc.findByOrg(orgId)` and drops any candidate absent from it.
That one null check is the tenant guard for all three scopes and removes the current
reliance on user ids happening to be globally unique.

### Write

`ORG_ADMIN` only, enforced twice:

- `SecurityConfig` — three method-scoped matchers before `.anyRequest().authenticated()`.
  A blanket `/api/directory/**` rule would be wrong, because `GET` must stay reachable by
  a plain `EMPLOYEE`.
- `DirectoryController` — `requireRole(UserRole.ORG_ADMIN)` on each write method.

Both, not either: the chain fails closed if a future write method forgets its check, and
the controller check is what produces the JSON error body (a chain 403 bypasses
`GlobalExceptionHandler` and returns a container error page).

### Self-service

Unchanged. `PATCH /api/employees/me` → `EmpFlow.updateProfile` →
`EmpService.updateProfile(userId, phone, address)`. Keyed by `requireUserId()` with
no id in the path, so an employee can only ever write their own row — no IDOR surface,
nothing to change. `phone` is P0 and appears in the directory; `address` is P2 and does
not. An employee editing their own P2 field is correct and unaffected.

### Deviations from `spring-backend-standards`, stated explicitly

- **Access decisions in the Controller, not the Flow.** The rulebook puts resource-level
  authorization in the `*Flow`. This codebase puts every access decision in the
  Controller and passes the Flow authorized primitives only — documented at
  `HrEmpController.java:32-35` and `HrEmpFlow.java:34-36`. Following project
  precedent; noted per the "a project's `CLAUDE.md` overrides" rule.
- **Exceptions.** Uses this project's `BadRequestException` / `NotFoundException` /
  `ConflictException`, not the rulebook's `ServiceException` + `ErrorCode` hierarchy,
  which does not exist here.
- **`rollbackFor`.** `Exception.class`, matching every existing Flow, not the rulebook's
  `Throwable.class`.
- **Pagination — this feature complies, existing code does not.** The rulebook requires
  `Pageable`/`Page` on any query that can return more than one row. `GET /api/directory`
  takes `page`/`size` (default 50, max 200) with a deterministic `ORDER BY name, id`.
  This diverges from `HrEmpFlow.list`, which is deliberately unpaged — new code
  follows the standard rather than inheriting the gap.

### Pre-existing security finding, not fixed here

`SecurityConfig` has `.csrf(csrf -> csrf.disable())` while authenticating from a cookie,
and the session cookie sets no `SameSite`. Every existing write endpoint already carries
this exposure; this feature adds three more. Enabling CSRF would break every existing
write endpoint in one commit, so it is **flagged, not silently widened** — it needs its
own change. Raised because the security baseline requires naming it.

## Field Sensitivity Classification

Every field carries a class. P2–P4 never appear in the directory list, detail or write
body — they live in a separate module with its own route gate (see Compliance Programme,
Phase 4).

**Exists** = a column in `user`/`employee` today. **Derived** = computable from existing
data, no column needed. **Phase** = where it lands in the programme below.

### 1. Identity & basic personal

| Field | Class | Status |
|---|---|---|
| Employee ID (`employee_code`) | P1 | Exists. Immutable + `^EMP-\d{5,8}$` in Phase 2. Existing rows (`DPZ-0142`, `EMP-1001`) violate the regex — validate on write only |
| First / last name | P0 | **Gap.** One nullable `name VARCHAR(128)` today. Splitting is a breaking change to 6 classes + frontend — Phase 2 |
| Preferred name | P0 | Gap — Phase 2 |
| Pronouns | P2 | Gap — Phase 4 |
| Profile photo | P0 display / P3 if biometric | Gap. No multipart anywhere in the app. Phase 4, and **face detection stays off** — see §Biometric |
| Date of birth | P2 | Gap — Phase 4 (sensitive module, not `employee`) |
| Gender (incl. non-binary / self-describe) | P2 | Gap — Phase 4 |
| Nationality (ISO 3166-1) | P2 | Gap — Phase 4. Validate against `java.util.Locale.getISOCountries()`; no new dependency |

### 2. Contact

| Field | Class | Status |
|---|---|---|
| Work email | P0 | Exists (`user.email`), unique per org via `uk_user_org_email`. Corporate-domain allowlist + lowercase normalisation in Phase 2. **Never encrypted** — it is the login lookup |
| Work phone | P0 | Exists. E.164 `^\+[1-9]\d{1,14}$` in Phase 2; existing `+91-90000-00001` style rows fail it, so validate on write only |
| Personal email | P2 | Gap — Phase 4 |
| Home address | P2 | **Structured — see §2a.** Today a single free-text `employee.address`. **Excluded from the directory payload in every scope**; employee-writable via `PATCH /api/employees/me` only |
| Emergency contact (name, relationship, phone) | P2 | Gap — Phase 4, own table, all three required together |

### 2a. Structured address

Replaces the single free-text `employee.address` column. Every column below is **P2** —
it never appears in `DirectoryData`, `EmpRowData` or `SessionData`, and is not
readable or writable through `/api/directory` in any scope. Lands in **Phase 4** on
`employee_sensitive` alongside the other P2 fields, as an `@Embeddable`
`pojo/AddressPojo` (the rulebook's "`@Embeddable` for fields that travel together"), not
as seven loose columns on `employee`.

| Column | Global mapping | Type | Required | Rules |
|---|---|---|---|---|
| `flat_no` | Sub-building details | `VARCHAR(50)` | no | Apartment, suite, flat, unit, room or floor. Frontend relabels to "Suite/Unit" for corporate offices — a label concern, not a second column |
| `address1` | Street Address 1 | `VARCHAR(150)` | **yes** | Premise number + street name (`100 Miller St`) |
| `address2` | Street Address 2 / Locality | `VARCHAR(150)` | no | Industrial parks, building names, secondary lines |
| `city` | City / Town | `VARCHAR(100)` | **yes** | **Not in the source spec — added.** `address2` (Locality) and `landmark` (Sub-locality) are neither a city; no postal format works without one. Flagged in Open Decisions rather than assumed silently |
| `landmark` | Neighborhood / Sub-locality | `VARCHAR(150)` | no | Materially improves deliverability in LATAM, India and MEA where street mapping is sparse. The frontend hides the input for countries where it is noise — a display rule, and **not** a validation rule: never reject a landmark because of the country |
| `postal_code` | Postal / ZIP code | `VARCHAR(20)` | conditional | Alpha-numeric (`SW1A 1AA`, `90210`). **Stored exactly as entered**, spaces and hyphens preserved — see the indexing note below |
| `state` | Administrative area | `VARCHAR(100)` | conditional | ISO 3166-2 subdivision code where one exists (`CA`, `ON`). See the ISO 3166-2 note below |
| `country` | ISO 3166-1 | `CHAR(2)` | **yes** | Alpha-2 only, upper-cased. **Never a country name as free text.** Validated against `java.util.Locale.getISOCountries()` — stdlib, no dependency, and the same call Nationality uses |

**Required is Java-side only.** `hbm2ddl.auto=update` cannot add a `NOT NULL` column to a
populated table (blocker 1), so all eight arrive nullable and `AddressPojo`'s required
fields are enforced in the Flow. The DB will not hold the line; the code has to.

**Conditional required is not per-country validation.** `postal_code` and `state` are
required for a short hardcoded set (US, CA, IN, GB, AU, BR) and optional elsewhere.
A full per-country requirement matrix is ~250 rows nobody will maintain and would reject
legitimate addresses from whichever country was entered wrong — expand the set on demand.

**ISO 3166-2 codes cannot be enforced from the JDK.** `Locale` ships country codes only,
not subdivisions. Enforcing `CA`/`ON` needs either a seeded `country_subdivision`
reference table (~5,000 rows) or a third-party dependency with a licence check.
Recommendation: store the string, validate only that it is present where the country
requires one, and defer code enforcement until something actually reports by subdivision.
Open Decision.

**No normalised postal-code column.** The source spec wants spaces and hyphens stripped
for search indexing. Addresses are P2 and appear on no search, list or filter surface in
this application — there is nothing to index, so a second `postal_code_norm` column would
be storage for a query that does not exist. Add it the day an address search does.
`// ponytail: no postal_code_norm; add it with the first address search, normalising at`
`// query time until then.`

**Migration.** Existing free-text `employee.address` values cannot be split into these
columns programmatically — a one-line blob has no reliable street/city/postal boundary.
`address` stays as a legacy read-only column, new writes populate the structured fields,
and rows are re-entered by their owners over time. There is no automatic backfill, and
Phase 4 needs Flyway before any of it is reviewable (blocker 2).

**Self-service write.** `ProfileForm` gains the address object, so
`PATCH /api/employees/me` writes `phone` + the eight address fields —
`EmpService.updateProfile`'s signature changes with it. Still keyed by
`requireUserId()` with no id in the path, so an employee can only ever write their own
address. This is the only write path for these columns; `/api/directory` never touches
them.

### 3. Employment & organizational

| Field | Class | Status |
|---|---|---|
| Job title (`designation`) | P1 | Exists as free text. Controlled taxonomy in Phase 3 |
| Department | P1 | Exists as free text. FK to a `departments` table in Phase 3 — **this reverses the v1.0 free-text decision and needs explicit sign-off**, plus a backfill from distinct existing strings |
| Reporting manager | P1 | Exists (`manager_id`, indexed `ix_emp_mgr`). Self-reference rejection + **cycle detection** in Phase 2 — best value-per-day in the whole spec |
| Skip-level manager | P1 | **Derived** from the `manager_id` chain. No column |
| Employment type | P1 | Exists as free text **by deliberate design** — new types are added as data, matched case-insensitively against `leave_type.applicable_employee_type`, no deploy. **Recommend declining the enum**: it reverses a twice-commented decision and couples the field to `LeaveTypePojo`, for no compliance gain |
| Employment status | P1 | Partial. `UserStatus` is `ACTIVE`/`ON_NOTICE`/`EXITED`; the spec wants `active`/`on_leave`/`suspended`/`terminated`. `on_leave` and `suspended` do not exist — Phase 2, with transitions logged to the audit log |
| Date of joining | P1 | Exists. Bounds check (not before org incorporation, not implausibly future) in Phase 2; `organization` has no incorporation date column yet |
| Probation end / confirmation date | P1 | Gap — Phase 2 |
| Work location → legal entity/branch | P1 | Gap — Phase 3, FK to a locations table |
| Grade/band, cost center, shift/timezone, previous employer | P1 | Gap — Phase 3 |
| Union membership status | P1 | Gap. **Note:** GDPR Art. 9 treats union membership as special category — classify P3, not P1, and put it in the sensitive module, not the directory. Deviation from the spec, deliberate |

### 4–8. Compensation, government IDs, health, EEO, legal

| Group | Class | Status |
|---|---|---|
| Salary (`basic_salary`) | P3 | Exists, already HR-only via `EmpRowData`. **Never in the directory payload, never writable via `/api/directory`** |
| Bank details | P3 | Gap — Phase 4. Encrypted; "tokenized" is **not** achievable (no vault) — do not claim it. Also note IBAN/SWIFT is the wrong field set for India-domestic payroll, which needs IFSC + account number |
| National ID (PAN / Aadhaar / SSN), tax ID | P3 | Gap — Phase 4. Per-country format incl. Aadhaar Verhoeff checksum, AES-256-GCM at rest, masked to last 4, reveal is a separate audited endpoint |
| Passport, visa/work-permit, right-to-work | P3/P4 | Gap — Phase 4 |
| I-9 / E-Verify | P4 | **Not applicable** — no US entity, no work-authorisation data. If one appears, reuse Phase 4's segregated module; do not pre-build |
| Health data, disability status, religion | P3 | Gap — Phase 4. HIPAA does not attach: no group health plan, no covered-entity or business-associate relationship |
| Blood group, marital status | P2 | Gap — Phase 4 |
| EEO race/ethnicity, veteran, disability self-ID | P3 voluntary | Gap — Phase 4, own table, aggregate-only reads with a k-anonymity floor (suppress buckets under n=5). **One clause is unimplementable today**: "never visible to anyone with hiring/comp authority" cannot hold when `HR` *is* the comp authority and no `EEO_REPORTING` role exists. Blocked on Phase 6 — do not report it green before then |
| Background checks, disciplinary, investigations, legal hold | P4 | Gap. FCRA **not applicable** — no consumer reporting agency, no report, no adverse action possible |

### 9–11. Skills, culture, assets

| Field | Class | Status |
|---|---|---|
| Skills, certifications, training, mentor | P1 | Gap — Phase 3 |
| Education history | P1/P2 | Gap — Phase 3 |
| Work anniversary | P0 | **Derived** from `date_of_joining`. No column |
| Birthday, month/day only | P2 | **Derived** from the Phase 4 DOB column; the year never leaves the sensitive module |
| Bio, LinkedIn handle | P0 | Gap — Phase 3 |
| Languages spoken, hobbies | P2 | Gap — Phase 3 |
| Asset IDs, licenses, badge number | P1 | Gap — Phase 3 |

### 12. Governance metadata

| Field | Status |
|---|---|
| Consent record (timestamp, IP, text version, purposes) | Gap — Phase 4. `CreatedOnlyPojo` is exactly the append-only superclass for it. "Immutable" is Java-side only; MySQL will happily `UPDATE` it |
| Retention period + deletion trigger per field | Gap — Phase 5, as a `retention_policy` data table. `hbm2ddl` cannot carry column metadata. A row in a config table is documentation with a foreign key, not an enforced control, until the purge job reads it |
| Data map / RoPA entry | Legal artifact — see Compliance Programme |

### Biometric

No photo column, no upload path, no face-detection code, no ML dependency. BIPA, GDPR
Art. 9 biometric provisions and DPDP biometric rules have **no subject matter here**.
"Face detection off by default" is satisfied by its non-existence, and the correct
engineering action is to keep it that way — a stored photo is not a biometric identifier
unless a face template is extracted from it. The Phase 4 `consent_record` table can carry
a `BIOMETRIC` purpose the day that changes.

## API / Interface

### `GET /api/directory`

Auth: any authenticated session. Scope resolved server-side (see Access Control Model).

| Param | Type | Match |
|---|---|---|
| `department` | string | case-insensitive exact |
| `managerId` | long | exact — **`ORG` scope only**, 403 otherwise |
| `search` | string | case-insensitive substring on name |
| `page`, `size` | int | default 0 / 50, max size 200 |

All optional, combinable with AND. `managerId` is admin-only and **fails closed with 403**
rather than being silently ignored — a response that quietly did not apply a filter the
caller asked for is a response that lies.

Response `200` — `Page<DirectoryData>`:

```json
{
  "content": [
    {
      "id": 42,
      "employeeCode": "EMP-1001",
      "name": "Alice Roy",
      "email": "alice.roy@acme.test",
      "phone": "+919000000001",
      "department": "Engineering",
      "designation": "Backend Engineer",
      "employeeType": "REGULAR",
      "status": "ACTIVE",
      "dateOfJoining": "2026-01-15",
      "managerId": 7
    }
  ],
  "totalElements": 1
}
```

### `POST /api/directory` — `ORG_ADMIN` only -- REMOVED 2026-09-30

Body `DirectoryForm`. Creates a `UserRole.EMPLOYEE` account plus its employee row.
Response `201`/`200` → `IdData`.

**Not in the body, each for a reason:**

- `password` — a roster endpoint must not carry a credential. The Flow mints one via
  `HrImportSupport.genTempPassword(email)` into a `byte[]`, zeroes it in a `finally`, and
  `persistRow` fires the existing verification mail. The employee gets in through
  verification / forgot-password.
- `role` — hardcoded `EMPLOYEE`. Granting `HR` stays on `/api/hr/employees`.
- `status`, `dateOfExit` — account lifecycle. `EXITED` is what `DELETE` does.
- `basicSalary`, `address` — P2/P3. A P0/P1 write surface must not accept them.

### `PATCH /api/directory/{id}` — `ORG_ADMIN` only -- REMOVED 2026-09-30

PATCH semantics: `null` = unchanged, via the existing
`EmpService.applyAdminFields(...)` called with `dateOfExit` and `basicSalary` always
`null` — that is the structural guarantee a directory edit can never reach salary or exit
date. `email` is ignored if sent (it is the login identity and needs the conflict handling
that lives in `HrEmpFlow.update`). Response → `DirectoryData`.

### `DELETE /api/directory/{id}` — `ORG_ADMIN` only -- REMOVED 2026-09-30

Soft delete: `is_active=false`, `status=EXITED`, `date_of_exit=today`, via
`HrEmpFlow.deactivate`. Idempotent. Response → `AckData`.

## Validation & Access Control

| Rule | Enforced in | Why there |
|---|---|---|
| Authenticated | `DirectoryController` — `requireUserId` / `requireOrgId` | only layer that may read the security context |
| Write = `ORG_ADMIN` | `SecurityConfig` matchers **and** `requireRole` | chain fails closed; controller yields the JSON error body |
| Read scope `ORG`/`REPORTS`/`SELF` | `DirectoryController.resolveScope` | needs `hasRole` + `existsManager`; passes an authorized enum down |
| `managerId` filter is admin-only | `DirectoryController.list` → 403 | depends on caller role |
| Target is in caller's org | `DirectoryController.requireInOrg` → **404** | object-level check, no cross-tenant existence leak |
| Cannot deactivate self | `DirectoryController.deactivate` → 400 | mirrors `HrEmpController:112-114`; without it an `ORG_ADMIN` can lock themselves out |
| Email required / unique in org | `DirectoryFlow.create` → 400 / 409 | data rule |
| `managerId` resolves in org, is not self | `DirectoryFlow` create + update → 400 | cross-entity data rule |
| Field lengths, password policy | existing `EmpService.clean`, `UserService.validatePwd` | already there |
| Type coercion, unknown JSON keys | Jackson at the HTTP boundary → 400 | `GlobalExceptionHandler.onBadJson` |

Logging: `orgId`, resolved `scope`, and the filter values. **Never** an email, name or
row list — matching the existing bulk-endpoint convention.

## Business Logic

`workflow/DirectoryFlow` — `@Service @Transactional(readOnly = true, rollbackFor = Exception.class)`,
write methods opt in individually (class-level write transactions are a rulebook
violation; note `HrEmpFlow` has one — do not copy it).

```java
Page<DirectoryData> list(Long orgId, DirectoryScope scope, Long viewerId,
                         String dept, Long managerId, String search, Pageable pg);
Long          create(Long orgId, DirectoryForm form);      // @Transactional write
DirectoryData update(Long orgId, Long targetId, DirectoryForm form);
void          deactivate(Long targetId);
```

`list`:

1. `userSvc.findByOrg(orgId)` → `Map<Long, UserPojo>` — the in-memory zip
   `HrEmpFlow.list` already does, and the tenant guard for every scope.
2. Candidates by scope: `ORG` → `empSvc.findByOrg(orgId)`; `REPORTS` →
   `empSvc.findByManager(viewerId)`; `SELF` → `empSvc.findByUserId(viewerId)`.
3. Drop any candidate whose user is absent from the map (**not this org**) or whose
   status is `EXITED`.
4. AND-combine the filters: `department` case-insensitive exact (a null department never
   matches); `managerId` exact; `search` case-insensitive substring, and a null `name`
   (real — `EmpService.createOrgAdminProfile` leaves it null) never matches.
5. Sort by `name, id`, page, map inline to `DirectoryData`.

`// ponytail: whole-org user load even for SELF/REPORTS, and in-memory filter+page. Push`
`// to a repo query with a Pageable if headcount makes it hot.`

`create` validates email non-blank + unique-in-org + `managerId` resolves in org, then
delegates to the package-private `HrEmpFlow.persistRow(orgId, EmpForm)` — the
single place that writes `user` + `employee` and sends the verification mail, already
reused this way by `EmpImportFlow`. It also supplies the `EMP-<8 hex>` code,
`REGULAR` type and `LocalDate.now()` joining defaults; do not re-implement them.

`update` rejects self-manager and unresolvable `managerId`, then calls `applyAdminFields`
with the two trailing nulls. `deactivate` is one line onto `HrEmpFlow.deactivate`.

**No new `*Repo` or `*Service` method. No new table, column, index or constraint** in this
feature. Everything below the Flow already exists:
`UserService.findByOrg` / `findById` / `existsInOrg`, `EmpService.findByOrg` /
`findByManager` / `findByUserId` / `existsManager` / `applyAdminFields`,
`HrEmpFlow.persistRow` / `deactivate`, `HrImportSupport.genTempPassword`.

## Data Models

`pojo/DirectoryScope` — `enum { ORG, REPORTS, SELF }`. A fixed value set is an enum, not
a string or a boolean pair.

`model/data/DirectoryData` — `@Getter @Setter`: `id`, `employeeCode`, `name`, `email`,
`phone`, `designation`, `department`, `employeeType`, `status`, `dateOfJoining`,
`managerId`.

**Never on this class:** `address`, `basicSalary`, `dateOfExit`, `role`, `emailVerified`,
and every P2–P4 field above. The absence of the setter *is* the control — contrast
`EmpRowData`, which carries `basicSalary`/`role`/`dateOfExit` and stays HR-only.
`managerId` is deliberately unresolved; the frontend cross-references the same list, as
the HR screen already does.

`model/form/DirectoryForm` — `@Getter @Setter @JsonIgnoreProperties(ignoreUnknown = true)`:
`email` (POST only), `name`, `phone`, `designation`, `department`, `employeeCode`,
`employeeType`, `dateOfJoining`, `managerId`.

Class names, against the 25-char ceiling: `DirectoryController` 19, `DirectoryFlow` 13,
`DirectoryData` 13, `DirectoryForm` 13, `DirectoryScope` 14. (v1.0's
`EmployeeDirectoryController` was 27 — over the limit. Hence the shorter prefix.)

## Database Indexes & Constraints

None. This feature adds no table, column, index or constraint — it reads `user` and
`employee` exactly as the HR list does, and writes through existing service methods.
`ix_emp_mgr` on `employee.manager_id` already backs the `REPORTS` scope.

Schema work starts in Phase 2 of the programme below, and is blocked on its prerequisites.

## Sensitive Module (Steps 4/5/6/9)

Scoped build of the P2–P4 wizard steps: Personal, Address (+ emergency contact), Pay &
IDs, and Voluntary self-identification (EEO). Field names below match the already-built
frontend fragments (`dir-personal.html`, `dir-address.html`, `dir-pay.html`,
`dir-voluntary.html`) and `DIRECTORY_SENSITIVE_FIELDS`/`DIRECTORY_MASKED_FIELDS` in
`js/directory-scope.js` exactly — that file is the field-list source of truth, not
reinvented here.

### Why a new table, not more columns on `employee`

Steps 3/7/8 added plain nullable columns directly to `employee` because P0/P1 data
carries no extra blast radius — every existing read of an `EmpPojo` (leave flows,
manager views, HR list) already sees those fields today, and that's fine for a job title
or a skill list. P2–P4 data is different in kind: bank account numbers, national IDs, and
EEO self-identification should not silently ride along on every unrelated
`empSvc.findByUserId`/`findByOrg`/`findByManager` call the leave domain already makes.
Same reasoning as `jpa-persistence.md` §14's auth-table isolation, applied to the
highest-sensitivity employee data instead of credentials.

New entity `pojo/EmpSensitivePojo`, table `employee_sensitive`, PK `user_id` (1:1
with `employee`/`user`, same pattern as `employee` itself — no separate generator,
assigned by the caller before save). Added via `hbm2ddl.auto=update` like every table in
this project (see `backend/CLAUDE.md`); all columns nullable, since nothing here is
collected at signup and every field is optional per the frontend's own design ("declining
to answer is a real answer").

### Data Models

`pojo/AddressPojo` — `@Embeddable`, the eight columns from §2a (`flatNo`, `address1`,
`address2`, `city`, `landmark`, `postalCode`, `state`, `country`), embedded inside
`EmpSensitivePojo`. Matches the doc's own §2a recommendation, not previously acted
on.

`pojo/EmpSensitivePojo extends AuditedPojo`:

| Group | Field | Type | Notes |
|---|---|---|---|
| PK | `userId` | `Long` `@Id` | FK -> `employee.user_id` / `user.id`, no `@ManyToOne` |
| Personal | `dateOfBirth` | `LocalDate` | |
| | `gender` | `VARCHAR(50)` | free text by design — frontend comment: "a fixed list cannot represent everyone" |
| | `pronouns` | `VARCHAR(40)` | free text, same reasoning |
| | `nationality` | `CHAR(2)` | ISO 3166-1 alpha-2, validated via `Locale.getISOCountries()` |
| | `maritalStatus` | `VARCHAR(40)` | free text |
| | `bloodGroup` | `VARCHAR(5)` | plain string, **not an enum** — the frontend's closed `<select>` already constrains it (`A+`…`O-`); a Java enum can't hold a `+`/`-` constant name without a custom converter (cf. `SituationType`'s `2_OR_MORE_CHILDREN` workaround in `schema.md`), and nothing server-side branches on the value. Not worth the ceremony for a field the UI already validates |
| | `personalEmail` | `VARCHAR(190)` | |
| | `languages` | `VARCHAR(255)` | comma-separated free text, matches `skills`/etc. convention |
| | `hobbies` | `VARCHAR(255)` | |
| Address | `address` | `AddressPojo` `@Embedded` | all 8 columns nullable at DB; `address1`/`city`/`country` required-in-Java when any address field is filled, `postalCode`/`state` conditionally required by country — same rule the frontend's `dirApplyAddressRules` already enforces client-side, mirrored server-side in `DirectoryFlow` |
| Emergency contact | `emergencyName` | `VARCHAR(128)` | all three required together, or none |
| | `emergencyRelationship` | `VARCHAR(50)` | |
| | `emergencyPhone` | `VARCHAR(20)` | |
| Pay & IDs | `bankAccountEnc` | `VARCHAR(255)` | AES-256-GCM ciphertext (IV + tag included, base64) via `@Convert` |
| | `bankIfsc` | `VARCHAR(20)` | plain — a routing code, not a secret |
| | `nationalIdEnc` | `VARCHAR(255)` | encrypted |
| | `taxIdEnc` | `VARCHAR(255)` | encrypted |
| | `passportEnc` | `VARCHAR(255)` | encrypted |
| | `visaStatus` | `VARCHAR(100)` | plain |
| | `sensitiveEncKeyId` | `VARCHAR(20)` | set to the active key version (`"v1"`) whenever any of the 4 encrypted fields is written. Not a rotation mechanism — the anchor column the doc's Hard Blocker #3 says a later rotation needs, with nothing else built yet |
| Voluntary / EEO | `ethnicity` | `VARCHAR(100)` | |
| | `veteranStatus` | `VARCHAR(100)` | |
| | `disabilityStatus` | `VARCHAR(100)` | |
| | `religion` | `VARCHAR(100)` | |
| | `unionMembership` | `VARCHAR(100)` | GDPR Art. 9 special category, per the doc's existing note |
| | `healthNotes` | `TEXT` | free text, no sane max |

`basicSalary` is **not** duplicated here — it already exists on `employee`
(`EmpPojo.basicSalary`), unchanged. This pass only widens who can write it (see
Business Logic).

No Java enum for `bloodGroup`/`gender`/`pronouns`/`maritalStatus`/`nationality` beyond
what's stated above — all are open or UI-validated free text, matching this project's
existing `employeeType` precedent.

### Encryption

`bankAccount`, `nationalId`, `taxId`, `passport` only — the four fields already in
`DIRECTORY_MASKED_FIELDS`. `bankIfsc`/`visaStatus`/`basicSalary` stay plain: a routing
code, a status word, and a number already handled unencrypted via `/api/hr/employees`
today.

**No blind index.** The doc's Hard Blocker #3 calls for a deterministic `*_bidx`
(HMAC-SHA256) column when an encrypted field needs an equality query. Nothing does:
`GET /api/directory` filters on `department`/`managerId`/`search` (name) only, never on
these fields. Adding blind-index columns now would be speculative — skipped, add if a
filter on one of these fields is ever requested.

`security/EncryptedStringConverter implements AttributeConverter<String, String>` —
AES-256-GCM, JDK `javax.crypto` only (no new dependency; the doc names this as the
already-available stdlib answer). 12-byte random IV generated per encryption, IV +
ciphertext + 16-byte GCM tag concatenated and base64-encoded into the single `VARCHAR`
column — no separate IV column needed. Applied via `@Convert(converter =
EncryptedStringConverter.class)` on the four `*Enc` fields, so `EmpSensitivePojo`'s
getters return plaintext transparently; encryption/decryption never appears above the
entity boundary.

Key: `app.crypto.sensitive-key=${SENSITIVE_ENC_KEY:}` in `app.properties`, same
env-var-only convention as `app.jwt.secret`/`db.password` — never committed, must decode
to exactly 32 bytes (AES-256) once set. Documented in `backend/CLAUDE.md` alongside the
other required environment variables.

### Masking — no reveal endpoint in this pass

`DirectoryData` never carries the real value of the four encrypted fields, for any
caller, including `ORG_ADMIN` — `DirectoryFlow.toData` masks server-side (`"•••• " +
last 4 chars`, matching the frontend's existing `maskTail`) before assigning. There is no
`GET` path that returns the plaintext.

**Writing a new value therefore cannot be round-tripped from a pre-filled edit form** —
if the frontend pre-fills these four inputs with the masked display value and the admin
resaves without touching them, that masked string would overwrite the real value. This
is a **frontend follow-up, not built here** (out of scope — a different repo, not
touched by this feature): the edit form must leave these four inputs blank unless the
admin is deliberately changing them, exactly like the existing admin-password-reset field
already does elsewhere in this app. Flagged in Relevant Files/Open Decisions below so it
isn't silently forgotten.

**No reveal-with-justification endpoint** (the doc's Phase 4 plan). Once saved, nobody —
including `ORG_ADMIN` — can retrieve the real value through this API; only overwrite it.
Matches the existing password-reset precedent (write-only, never read back), and avoids
needing the audited-reveal infrastructure Phase 4 would require. Recorded as a real
product limitation, not an oversight: an admin who needs to verify a stored value has no
way to do so except asking the employee again.

### Access Control — the one real gap this closes

**Today there is no server-side gate distinguishing `ORG_ADMIN` from `HR` on read**
— both resolve to `DirectoryScope.ORG` (`DirectoryController.resolveScope`), and nothing
in `DirectoryFlow.list`/`toData` looks past the scope. That was harmless while only P0/P1
fields existed. It stops being harmless the moment sensitive fields exist, because
`HR` must **not** receive them (the frontend already documents this exact
distinction in `directory-scope.js`: "gating on scope instead of role here would hand HR
every employee's bank details" — the backend has never enforced it because there was
nothing to enforce).

Fix: `DirectoryController.list` computes `boolean includeSensitive =
hasRole(UserRole.ORG_ADMIN)` (not `hasRole(HR)`) and passes it into
`DirectoryFlow.list`, which only merges sensitive fields into `DirectoryData` when true.
Write access is unaffected — `POST`/`PATCH` are already `requireRole(ORG_ADMIN)` only,
which already excludes `HR` entirely.

### Business Logic

`DirectoryFlow.list(orgId, scope, viewerId, dept, managerId, search, pg, includeSensitive)`
— one new parameter. After paging (unchanged), if `includeSensitive`, batch-fetch
`EmpSensitivePojo` rows for just the page's ids (`sensitiveRepo.findByUserIdIn(ids)`,
≤ page size ≤ 200 — naturally bounded, tighter than the existing whole-org load this
method already does for `EmpPojo`) and merge masked/plain fields into each row's
`DirectoryData`.

`DirectoryFlow.create` — after `applyDirectoryFieldsFrom` (steps 3/7/8), also
`sensitiveSvc.createEmpty(id)` then applies the four new field groups against the
freshly-persisted row, same "second step against the persisted row" pattern already used
for steps 3/7/8.

`DirectoryFlow.update` — `sensitiveSvc.findByUserIdOrNull(targetId)`, creating the row
lazily if absent (covers accounts created before this feature shipped), then applies the
four field groups. **`applyAdminFields`'s trailing `basicSalary` argument changes from
hardcoded `null` to `form.getBasicSalary()`** — the one line that reverses the prior
salary decision; everything else about that call is unchanged.

New `service/EmpSensitiveService` (parallel to `EmpService`, not folded into
it — `EmpService` is already 227 lines and "employee profile" plus "encrypted
sensitive profile" is exactly the second `and` the class-cohesion rule flags):

```java
EmpSensitivePojo findByUserIdOrNull(Long userId);
EmpSensitivePojo createEmpty(Long userId);
Map<Long, EmpSensitivePojo> findByUserIds(Collection<Long> userIds);   // batch, for list()
void applyPersonalFields(EmpSensitivePojo e, LocalDate dob, String gender, String pronouns,
                         String nationality, String maritalStatus, String bloodGroup,
                         String personalEmail, String languages, String hobbies);
void applyAddressFields(EmpSensitivePojo e, AddressPojo address,
                         String emergencyName, String emergencyRelationship, String emergencyPhone);
void applyPayFields(EmpSensitivePojo e, String bankAccount, String bankIfsc,
                     String nationalId, String taxId, String passport, String visaStatus);
void applyVoluntaryFields(EmpSensitivePojo e, String ethnicity, String veteranStatus,
                           String disabilityStatus, String religion, String unionMembership,
                           String healthNotes);
```

Split into four apply methods (one per wizard step group), each with ≤6 primitive
parameters, rather than one ~29-parameter method — `applyDirectoryFields` already sits at
16 params for 3 combined steps, and doubling that would be the "two parameters of the
same primitive type next to each other" problem coding.md §14 flags, at scale.
`applyAddressFields` takes the assembled `AddressPojo` as one parameter instead of 8,
which is exactly what the `@Embeddable` buys here — `DirectoryFlow` builds it from the
form's 8 loose fields before calling down, same as it already builds `EmpForm`
before calling `HrEmpFlow.persistRow`.

All four `apply*` methods use the same null-means-unchanged PATCH semantics as
`applyAdminFields`/`applyDirectoryFields` — **except** the address's Java-side
required-together validation, and the emergency-contact required-together validation,
which live in `DirectoryFlow` (mirroring the frontend's `dirApplyAddressRules`) since
they span multiple fields and belong with the other cross-field checks
(`managerId`/`mentorId` resolution) already there.

Server-side validation of the fields already flagged as deferred in the doc stays
deferred here too — no Aadhaar Verhoeff checksum, no per-country ID format, no E.164
phone check on `emergencyPhone`. Not requested, and the doc already lists these as gaps
elsewhere in the same programme.

### API / Interface changes

`POST`/`PATCH /api/directory` bodies gain the ~29 fields listed above (`DirectoryForm`),
plus `basicSalary` (existing field, newly accepted). `GET /api/directory` response gains
the same fields on `DirectoryData` **only when the caller is `ORG_ADMIN`** — absent
(not merely null) for every other caller, matching how `DirectoryData` already omits
fields entirely for non-P0/P1 data today. The four encrypted fields always carry the
masked form (`"•••• 1234"`) or `null`, never the real value.

### Validation & Access Control — additions

| Rule | Enforced in | Why there |
|---|---|---|
| Sensitive fields readable only by `ORG_ADMIN`, not `HR` | `DirectoryController.list` → `includeSensitive` flag | role check must happen before scope collapses `ORG_ADMIN`/`HR` together |
| Address fields required-together (`address1`/`city`/`country`, conditionally `postalCode`/`state`) | `DirectoryFlow` create/update | Java-side only — `hbm2ddl.auto=update` cannot add `NOT NULL` to a populated table |
| Emergency contact required-together (all 3 or none) | `DirectoryFlow` create/update | same reasoning |
| Encrypted fields never returned in plaintext | `DirectoryFlow.toData` | masking is the actual control, not the frontend's client-side `maskTail` |
| `basicSalary` writable via `/api/directory` | `DirectoryFlow.update` → `applyAdminFields` | explicit reversal of the prior decision, see status update above |

### Configuration Parameters

`app.crypto.sensitive-key` — `SENSITIVE_ENC_KEY` env var, base64 AES-256 key (32 bytes),
same never-committed convention as `app.jwt.secret`. Required in every real deployment
once this ships; blank in test (`test.properties` gets a fixed dummy key — a real secret
has no reason to exist in a test file).

### Database Indexes & Constraints (this addition)

`employee_sensitive`: PK `user_id` only, DB-level FK -> `employee.user_id`. No other
index — nothing filters or joins on any of these columns (§12: an index needs a query
behind it, and there is none). No uniqueness beyond the PK.

### Open Decisions (this addition)

8. **Frontend edit-form pre-fill for the 4 masked fields** — needs a follow-up change in
   `leave-frontend` (not this repo) so `dir-bank-account`/`dir-national-id`/`dir-tax-id`/
   `dir-passport` start blank on Edit rather than pre-filled with the masked value.
   Flagged, not fixed here.
9. **No reveal endpoint** — recommend accepting this for now (matches the password-reset
   precedent); say if a justified-reveal endpoint is actually wanted, since it's real
   scope (audit logging that Phase 1 hasn't built yet).
10. **Key rotation** — `sensitiveEncKeyId` is an anchor column only; no rotation job
    exists. Fine until a key ever needs rotating, at which point it's the first thing
    read.

### Relevant Files (this addition)

- `pojo/EmpSensitivePojo.java`, `pojo/AddressPojo.java` — new.
- `security/EncryptedStringConverter.java` — new.
- `service/EmpSensitiveService.java`, `repo/EmpSensitiveRepo.java` — new.
- `model/form/DirectoryForm.java`, `model/data/DirectoryData.java` — +29 fields each.
- `workflow/DirectoryFlow.java` — `includeSensitive` threading, address/emergency
  validation, sensitive create/update/list wiring, `applyAdminFields` salary line.
- `rest/DirectoryController.java` — `includeSensitive` computed from `hasRole(ORG_ADMIN)`.
- `app.properties`, `test.properties` — `app.crypto.sensitive-key`.
- `backend/CLAUDE.md` — document `SENSITIVE_ENC_KEY` alongside the other required env vars.
- `prompts/schema.md` — add `employee_sensitive`.
- **Not touched, flagged as a needed follow-up:** `leave-frontend/fronted/js/api/directory.js`
  (masked-field edit pre-fill, Open Decision 8) — different repo, out of scope here.

### Test list (this addition)

`DirectoryFlowTest` — `list_asOrgAdmin_includesSensitiveFields`,
`list_asHr_excludesSensitiveFields` (the regression guard for the access-control
gap this closes), `list_asManagerOrEmployee_excludesSensitiveFields`,
`create_encryptsBankAccountNationalIdTaxIdPassport`,
`create_sensitiveFieldsMaskedInResponse`,
`update_addressRequiresStreetCityCountryTogether`,
`update_addressPostalAndStateRequiredForListedCountries`,
`update_emergencyContactRequiresAllThreeOrNone`,
`update_basicSalaryWritableViaDirectory` (confirms the reversal),
`update_blankMaskedFieldLeavesEncryptedValueUnchanged`,
`update_sensitiveRowCreatedLazilyForPreExistingAccount`.
`EmpSensitiveRepoTest` — save/find, null-constraint tests are skipped (every column
nullable except the PK), `save_nullUserId_throws`.
`EncryptedStringConverterTest` — `roundTrip_returnsOriginalPlaintext`,
`differentCalls_produceDifferentCiphertext` (random IV), `nullInput_storesNull`.

## Compliance Programme — v2.0 spec triage

The v2.0 specification is a multi-regime enterprise HRIS blueprint. Planned in full here,
per explicit instruction, with each item categorised honestly:

**(A)** backend code here · **(B)** schema · **(C)** infrastructure outside this repo ·
**(D)** legal/organisational, not deliverable by code at any effort · **(E)** not
applicable, with the reason.

### Not applicable — closed, with reasons

- **Biometric / BIPA (§2.4)** — (E). No photo column, no upload path, no face detection,
  no template. No subject matter.
- **EU AI Act high-risk + GDPR Art. 22 (§3.2)** — (E). No model, no inference dependency,
  no profiling, no automated decision with legal effect. The only automation is pro-rata
  accrual arithmetic and a documented "no manager → auto-approve" rule; the approval chain
  is human-in-the-loop by construction. The trigger for this item is "we added an AI
  system", not "we planned for one".
- **FCRA, I-9/E-Verify, pay transparency (§3.4)** — (E). No consumer reporting agency, no
  report, no adverse action; no US entity or work-authorisation data; no job postings or
  requisitions, and `basicSalary` is already HR-only.
- **PIPL limb of cross-border (§3.3)** — (E). No Chinese establishment or data subjects.

### Legal / organisational — (D), zero backend days

Legal-basis matrix across nine regimes; cross-border mechanics (SCCs, adequacy, DPF, DPDP
restricted-country list); RoPA register (Art. 30, and Art. 30(5) may exempt an org this
size); the DSAR *service level* as distinct from the endpoints; named DPO with published
contact; privacy notice; vendor DPAs (**Mailjet is a processor of employee email addresses
and needs one**); DPIA; **works-council consultation, which is a gate on read-logging, not
a task**; recurring training; incident-response plan and the 72-hour notification
decision; vendor risk assessment; the anonymisation-versus-deletion legal position.

**A backend plan cannot mark a single one of these done.** Any status report claiming
otherwise is the compliance failure the programme exists to prevent.

### Infrastructure — (C), owners named

KMS/HSM + key rotation (platform; backend must expose `enc_key_id` per encrypted value
**from Phase 4**, or rotation later has no anchor) · SSO/OIDC + MFA (IdP team; ~11 days of
(A) alongside it) · WORM audit storage (DBA + platform) · DLP (security) · **encrypted,
isolated backups with a tested restore — the highest-value control on the entire list and
zero lines of application code** · TLS termination (platform; HSTS headers are (A)) ·
device/IP anomaly detection (SIEM) · VAPT (external) · SOC 2 and ISO 27001 (6–18 month
programmes, external auditor).

### Hard blockers in this stack

1. **`hbm2ddl.auto=update` only adds.** Never drops, narrows, retypes, or creates a CHECK
   or FK it wasn't told about. So every new column is `nullable = true` on a populated
   table, and the "mandatory field schema" is enforceable **only in Java, never in the
   DB**. Adding a unique constraint over duplicate data logs an error and continues — a
   silent no-constraint.
2. **No Flyway means no reviewable migration and nowhere for a backfill to live.** Phases
   2, 3, 4, 5 all need backfill (department strings → FK ids, email lowercasing,
   `employee_code` reformatting). Note `prompts/libraries.md` and
   `features/database-schema.md` **already claim Flyway exists and that hbm2ddl is not
   used** — both are wrong and mislead anyone planning schema work. The rulebook says do
   not add a migration framework without escalating: **this is the escalation.**
3. **Field-level encryption breaks equality queries and unique indexes.** AES-GCM's random
   IV means the same plaintext encrypts differently every time. `user.email` must
   therefore **never** be encrypted — it is the login lookup and the `uk_user_org_email`
   constraint. Anything else encrypted needs a second deterministic `*_bidx`
   (HMAC-SHA256) column to query on, which itself leaks equality.
4. **Append-only/WORM is not achievable in a plain MySQL table** the app owns DDL on.
   Approximation is a DBA `REVOKE UPDATE, DELETE` plus a `BEFORE UPDATE ... SIGNAL`
   trigger, which collides with Hibernate's DDL rights. Real WORM means shipping the log
   to object-lock storage.
5. **Erasure fights three deliberate decisions**: no `ON DELETE CASCADE` anywhere, no row
   is ever hard-deleted (leave history keeps its FKs), and `leave_ledger_entry` is
   append-only. So erasure can only be **anonymisation in place** — defensible, but a
   decision for counsel.
6. **Internal libraries are unavailable.** `CLAUDE.md`: this project is not in the
   deployza org and has no Artifact Registry access. That blocks
   `deployza-lib-fileclient` (photos), `-quartz` (retention jobs), `-lang` (crypto),
   `-loggingclient` (the only realistic WORM path), `-oauth2` (SSO), `-sheet`/`-template`
   (DSAR export). Two have good stdlib/platform answers — **JDK `javax.crypto`** and
   **Spring's own `@EnableScheduling`** — take those. The rest need third-party deps with
   a licence check. **Requesting registry access costs zero dev days and deletes three of
   these decisions.**

### Phases

Backend days only. No frontend, no data remediation, no QA.

| Phase | Content | Days |
|---|---|---|
| **0 — prerequisites & free wins** | Fix `.csrf(disable)` **(highest value per line in this document)**; adopt Flyway, baseline, `hbm2ddl.auto=validate`; security headers + HSTS + audit every env for `APP_COOKIE_SECURE=false`; correct the Flyway claims in `libraries.md` / `database-schema.md`; request registry access; close the (E)/(D) items above | **7** |
| **1 — audit log becomes real** | The table exists and **nothing writes it**. Add `organizationId` (missing — the audit log is the one table not tenant-scoped), `actorRole`, `targetUserId`, `fieldName`, `accessType`, `justificationCode`, `ipAddress`, `requestId` (free — `RequestIdFilter` already makes one per request). New `AuditLogService`, first real writers, the `(entity_type, entity_id)` index. Unblocks phases 2/4/5 and every incident-response conversation. **If only one phase ever ships, ship this one.** | **8** |
| **2 — validation on fields that already exist** | `employee_code` regex + immutability, E.164 phone, email normalisation + domain allowlist, DOJ bounds, self-manager rejection, **manager cycle detection (1.5 d, best value/day in the spec)**, status transitions logged to Phase 1. No new tables. Recommend **declining** the `employment_type` enum | **10** |
| **3 — reference tables** | `departments`, `job_titles`, `work_locations`/legal entity + HR CRUD + backfill from distinct free-text values. **Reverses v1.0's free-text department decision — needs explicit sign-off, not a silent change** | **14** |
| **4 — the sensitive module** | `employee_sensitive`, **the structured address `@Embeddable` (§2a) plus the `ProfileForm`/`updateProfile` signature change it forces**, `national_id`, `bank_account`, `emergency_contact`, `consent_record`, `eeo_self_id`; `AesGcmConverter` + blind index + `enc_key_id`; masking + reveal-with-justification against Phase 1; own controller and route gate; a reflection test keeping these fields out of the directory/HR/session payloads | **27** |
| **5 — DSAR & retention** | `dsar_request` + per-jurisdiction SLAs (GDPR 30+60, CCPA 45+45, PIPEDA 30+30, LGPD 15; **DPDP unspecified — encode null and fail loudly, do not invent a number the statute does not give**), JSON export, anonymise-not-delete erasure, `retention_policy`, one nightly `@Scheduled` job. Blocked on legal supplying the periods | **17** |
| **6 — RBAC overhaul** | 10 roles, multi-role via a join table (the JWT claim becomes an array — **invalidates every live session**; `tokens_valid_after` already exists to do that cleanly), field-level permission matrix applied at every `*Data` build site, ABAC region overlay, segregation of duties, break-glass with second approver, time-boxed elevation. Rewrites every controller's authorization and needs a matching frontend programme. Retroactively completes the EEO "invisible to comp authority" clause | **25** |

**Backend total ≈ 108 dev-days ≈ 5 calendar months for one developer**, on a codebase of
~30 main classes whose `employee` table has 12 columns. Frontend for phases 2/3/4/6 is at
least as large again. Data remediation for 2/3 is unbounded until someone counts the rows.

**The honest split:** the genuinely valuable work — Phase 0's CSRF fix, Phase 1's audit
log, Phase 2's cycle detection and validation — is **25 of those 108 days and delivers
most of the actual risk reduction**. The other 83 build enterprise HRIS field
infrastructure and an IAM platform. Recommend phases 0–2, then re-decide 3–6 against real
need rather than the checklist.

**This feature — the `/api/directory` RBAC change — is none of the above.** It is ~3 days,
depends on no phase, and is what the Access Control Model, API and Business Logic sections
above specify. The programme is planned, not scheduled.

## Open Decisions

1. **`preferredName` / `workLocation` on `DirectoryData`** — the spec lists them as P0/P1
   but neither column exists. Recommend **omitting both** (10 of 12 P0/P1 fields ship; two
   columns nothing reads or writes is speculative storage). If wanted now: two nullable
   `VARCHAR(128)` columns, writable only through `DirectoryForm`.
2. **`employment_type` as an enum** — recommend **declining**; reverses a documented
   design decision for no compliance gain.
3. **Department as an FK** (Phase 3) — reverses v1.0's free-text decision. Needs sign-off.
4. **Flyway** — the rulebook says escalate rather than add a migration framework. This is
   the escalation; phases 2–5 are un-backfillable without it.
5. **CSRF** — needs its own change before or alongside this feature.
6. **`city` on the structured address** (§2a) — absent from the source field list, which
   jumps from Locality to Sub-locality to Postal Code. Added as required, because no
   postal format works without it. Confirm, or say why it was left out.
7. **ISO 3166-2 state codes** (§2a) — not enforceable from the JDK. Recommend storing the
   string and deferring code enforcement; the alternative is a ~5,000-row reference table
   or a new dependency, for a P2 field nothing currently reports on.

## Relevant Files

- `prompts/features/employee-directory.md` — this document.
- `prompts/api-endpoints.md` — add the four `/api/directory` rows next to the
  `/api/hr/employees` ones.
- `pojo/DirectoryScope.java`, `model/data/DirectoryData.java`,
  `model/form/DirectoryForm.java` — new.
- `workflow/DirectoryFlow.java`, `rest/DirectoryController.java` — new.
- `config/SecurityConfig.java` — three method-scoped `ORG_ADMIN` write matchers.
- `rest/AbstractController.java:56-58` — stale comment claiming roles are not Spring
  authorities; `JwtAuthFilter:54-59` does populate them. Fix, or the next person puts the
  write gate in the wrong place.
- `src/test/java/com/leaveapp/rest/DirectoryControllerTest.java`,
  `src/test/java/com/leaveapp/workflow/DirectoryFlowTest.java` — new.
- **Untouched:** `HrEmpController`, `HrEmpFlow`, `EmpController`,
  `EmpFlow` (read-only reuse of `persistRow`, `deactivate`, `genTempPassword`).

### Test list (for the implementation pass)

`DirectoryControllerTest` — scope resolution and the write gate:
`list_asOrgAdmin_scopeIsOrg`, `list_asHr_scopeIsOrg`, `list_asManager_scopeIsReports`,
`list_asEmployee_scopeIsSelf`, `list_asOrgAdminWithReports_scopeIsOrg`,
`list_managerIdFilterAsManager_forbidden`, `create_asHr_forbidden`,
`update_asHr_forbidden`, `update_crossOrgTarget_notFound`,
`deactivate_asHr_forbidden`, `deactivate_ownAccount_badRequest`.

`DirectoryFlowTest` — scoping, filters, tiering, writes:
`list_reportsScope_returnsOnlyDirectReports`,
`list_reportsScope_excludesCrossOrgReport` (the `findByManagerId` tenant guard),
`list_selfScope_returnsOnlyOwnRow`, `list_orgScope_excludesExited`,
`list_departmentFilter_caseInsensitiveExact`, `list_searchFilter_nullNameNeverMatches`,
`list_filtersCombineWithAnd`, `list_noMatchFilters_returnsEmptyNotError`,
`list_dataCarriesOnlyP0P1Fields` (reflective assertion that `DirectoryData` has no
`basicSalary`/`address`/`role`/`dateOfExit` accessor — the regression guard for the whole
tiering rule), `create_writesUserAndEmployeeWithEmployeeRole`,
`create_duplicateEmailInSameOrg_conflict`, `create_crossOrgManagerId_badRequest`,
`update_neverTouchesSalaryOrExitDate`, `update_selfManager_badRequest`,
`deactivate_thenAbsentFromDirectoryList`.
