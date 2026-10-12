# HR Employee Management

> **Updated 2026-09-30 -- read this first; it overrides the sections below where they disagree.**
> - **No HR create.** `POST /api/hr/employees` is removed. Only an `ORG_ADMIN` creates users, by
>   invite: `POST /api/admin/users {name, email, role}` (+ `POST /api/admin/users/{id}/resend-invite`)
>   and `POST /api/hr/employees/import` (ORG_ADMIN only). The user is created `INVITED` and sets their
>   own password from the invite mail; see `employee-onboarding.md`.
> - **No passwords here at all.** `EmpForm.password` is gone: no password on create, and no
>   administrative password reset on `PATCH` -- a user resets their own via forgot-password.
>   (`UserService.setPassword` was deleted; `UserService` is now identity only, `CredentialService`
>   owns login/hashing/set/reset password.)
> - **Status rules on `PATCH /api/hr/employees/{id}`:** `status` accepts `ACTIVE`/`ON_NOTICE`/`EXITED`
>   only, and for a user who is `INVITED`/`PENDING`/`ONBOARDED` it can only be set to `EXITED`
>   (`400` otherwise) -- HR cannot skip onboarding. `DELETE` is unchanged (soft delete to `EXITED`).
> - **New: `PATCH /api/hr/employees/{id}/details`** (`ORG_ADMIN`, or `HR` on `EMPLOYEE` rows) --
>   edit what the user filled in during onboarding (`phone`, `personalInfo`, `address`,
>   `emergencyContact`, `payIds`; every section optional, `null` = unchanged; unknown properties
>   `400`; the onboarding field rules apply). Each call writes an `audit_log` row listing section
>   names only.
> - Reporting manager and employment fields (designation, department, joining date, employee type,
>   salary, ...) stay on `PATCH /api/hr/employees/{id}`; HR/admin fill them after the user
>   onboards.
> - The `password`, "created ACTIVE" and HR-creates-employee parts of Overview / Edge Cases / REST API /
>   Workflow / Relevant Files below are historical.

## Status: Built (2026-08-31) — `POST` `GET` `PATCH` `DELETE /api/hr/employees[/{id}]`

This pass built **`POST /api/hr/employees`**: an HR admin (or org admin) creates a
single `EMPLOYEE` / `HR` account in their own organization. It covers the "Add
employee" form in `leave-frontend/fronted` (`js/api/hr-employees.js` →
`apiClient.createHrEmployee`).

**Added 2026-08-31:** `GET /api/hr/employees` — the list backing the HR employees
screen (`apiClient.hrEmployees()`). Same `ORG_ADMIN` / `HR` gate, org taken from
the JWT. Returns `EmpRowData[]` (id, code, name, email, phone, role, status,
managerId, department, designation, employeeType, dateOfJoining, dateOfExit,
basicSalary) for every account in the org. Unpaged and unfiltered — the screen does
search / manager filtering client-side, and derives the "Manager" badge and manager
names from the whole list, so no per-row manager or org lookup server-side.
`HrEmpFlow.list` zips `UserService.findByOrg` + `EmpService.findByOrg` in
memory by id (two queries). `ponytail: unpaged list; add a Pageable overload if an
org's headcount ever outgrows one response.`

**Added 2026-08-31 (same day):** `PATCH /api/hr/employees/{id}` — edits one account in
the caller's org, backing the same modal's "Save changes" mode
(`apiClient.updateHrEmployee`). Reuses `EmpForm`; every field optional, `null` (or
blank, for strings the frontend clears) = leave unchanged. Notable decisions:
- **Password** present & non-empty → administrative reset via new
  `UserService.setPassword` (validates + BCrypts + wipes, **no** current-password
  check — sibling of `changePassword`).
- **`status`** is now honoured (`ACTIVE` / `ON_NOTICE` / `EXITED`, validated). `PATCH`
  does **not** write `user.is_active` — soft-delete stays the deferred `DELETE`
  endpoint's job, so the two don't fight over the same column.
- **Role** obeys POST's rules (controller: `ORG_ADMIN` in payload → 400, `HR`
  granting `HR` → 403). Plus: an `HR` caller may modify **`EMPLOYEE` rows
  only** — touching an `HR`/`ORG_ADMIN` row → 403. The controller passes a
  `callerIsOrgAdmin` boolean into `HrEmpFlow.update` (the Flow still never reads
  the security context).
- **Cross-org / unknown `{id}`** → `404` (`NotFoundException`), no cross-org existence
  leak. Bad path-var type → `400` (`MethodArgumentTypeMismatchException`).
- **`managerId`**: `== id` → 400 (self-manager, the one cycle checked cheaply); else
  same-org check as POST. Nullable columns (`dateOfExit`, `managerId`, `basicSalary`)
  **cannot be cleared** through PATCH — `null` means "unchanged" for every field.
  `ponytail: no explicit-null sentinel; add one only if "remove this manager / exit
  date" becomes a real workflow.`
- **`employeeCode`** change: `uk_employee_org_code` collision surfaces as `500`, same
  stance as POST.
- Returns the updated `EmpRowData` (POST returns `{id}`; the frontend ignores both
  bodies).

**Added 2026-08-31 (same day):** `DELETE /api/hr/employees/{id}` — soft-delete:
`user.is_active=false`, `user.status=EXITED`, `employee.date_of_exit=today`. Never a
hard `DELETE` (leave history keeps its FKs). Notable decisions:
- **Idempotent.** An already-`EXITED` account is a no-op `200` — its original
  `date_of_exit` is kept, not overwritten with today.
- **No request body.** The `checkPayloadRole` checks don't apply; the shared
  `HrEmpFlow` target guard (via `loadTargetInOrg`, also used by `PATCH`) still
  does — cross-org / unknown `{id}` → `404`, and an `HR` caller may only
  deactivate `EMPLOYEE` rows (`403` for `HR` / `ORG_ADMIN` targets).
- **Self-deactivation blocked.** `{id}` == the caller's own user id → `400` (checked in
  the controller from `requireUserId()`, pure request data).
- **Login already blocked.** `UserService.verifyLogin` rejects `status == EXITED`, so
  no `verifyLogin` change was needed (the `!isActive()` guard the earlier pass deferred
  stays deferred — `EXITED` covers this path).
- No `*Data` returned — `Map.of()` body, matching `AuthController.logout`'s style.

The paired **`PATCH /api/hr/employees/{id}/reactivate`** (undo: `is_active=true`,
`status=ACTIVE`, clear `date_of_exit`) is **not** built — the user asked for `DELETE`
only.

The rest of the HR employee surface (get-by-id, reactivate, bulk `reassign-manager`,
`String`→enum conversion) is still **deferred** — see the [Deferred](#deferred) section.
A previous, much larger version of this doc planned all of it at once; that scope was
cut by the user. The CSV temp-password bulk-create path is **built** — see
`prompts/features/bulk-employee-import.md`.

Design points carried over from that larger plan and still in force here:

1. **Credentials.** No invite-link / email flow. The create payload carries a
   `password`; it is **required** for this endpoint (the Add-employee form always
   collects one). `user.password_hash` stays `NOT NULL`. The "generate a `tempPassword`
   when absent" path is the bulk-import endpoint — see
   `prompts/features/bulk-employee-import.md`.
2. **Authorization.** `ORG_ADMIN` may create `HR` and `EMPLOYEE` rows; `HR`
   may create `EMPLOYEE` rows only. No one creates an `ORG_ADMIN` through this API.
3. **Path & scope.** `POST /api/hr/employees`. `organizationId` is always the JWT claim,
   never a field in the URL or body.

## Overview

An "employee" is a `user` row (auth: email, password hash, role, status, `is_active`)
plus a 1:1 `employee` row (profile: code, name, phone, dates, type, designation,
department, manager, salary), both keyed by the same id. This endpoint creates the pair
as a unit inside one transaction, scoped to the caller's organization.

`role` finally gets `HR` / `EMPLOYEE` written to it by a real endpoint — until now
only `ORG_ADMIN` (via signup) was ever written. No new tables, no new columns, **no
Flyway migration.**

## Edge Cases

- **Route authorization (controller, before the Flow runs).**
  - Caller role must be `ORG_ADMIN` or `HR` — `403` otherwise (`requireAnyRole`).
  - `role = ORG_ADMIN` in the payload → `400` ("cannot create an org admin here").
  - Caller is `HR` and payload `role = HR` → `403` (only an `ORG_ADMIN`
    creates `HR` accounts).
  - Payload `role` neither `EMPLOYEE` nor `HR` (or absent) → `400`.
- **Org scoping.** The `user` and `employee` rows are written with `organizationId` from
  the JWT. Any `organizationId` in the body is ignored. `managerId`, if present, must
  resolve to a `user` in the **same** org (any role — a manager may be an `HR`);
  a cross-org or unknown `managerId` → `400`. `managerId` omitted / `null` = top of
  hierarchy. No DB-level FK on `employee.manager_id` (unchanged project policy), so this
  is a service-layer check. No manager-cycle check (there is no hierarchy-walking
  feature yet) — noted as a known gap.
- **Email uniqueness.** The Flow pre-checks `(organizationId, email)` via
  `UserService.existsInOrgByEmail` and throws `ConflictException` (`409`). The existing
  `uk_user_org_email` unique constraint is the race backstop; a lost race surfaces as a
  `500` (`DataIntegrityViolationException` is not mapped) — accepted for this pass, same
  stance the larger plan took for the generated-code collision. Two different orgs may
  still reuse an email (unchanged).
- **`employeeCode`.** Required by the Add-employee form. If absent (belt-and-braces),
  the Flow generates `EMP-<8 hex>`. `uk_employee_org_code` is the uniqueness guarantee;
  a generated-code collision (~1e-10) surfaces as a `500` — not retried.
  `ponytail: random employee_code, switch to a per-org counter if a collision is ever
  actually seen.`
- **Defaults.** Missing `dateOfJoining` → today. Missing `employeeType` → `REGULAR`
  (kept a `String` — see [POJOs](#pojos)). `name` may be `null` (column is nullable).
- **Ignored payload fields.** `forcePasswordReset` (column dropped in V13) and `status`
  (new accounts are always created `ACTIVE`) are accepted and ignored.
  `EmpForm` is annotated `@JsonIgnoreProperties(ignoreUnknown = true)` so these and
  any other extra keys do not trigger the `ObjectMapper`'s default
  `FAIL_ON_UNKNOWN_PROPERTIES` → `400`.
- **Password.** `EmpForm.password` is `byte[]` via the existing `PwdDeserializer`,
  handed straight to `UserService.createUser`, which hashes with `BCryptPasswordEncoder`
  and zeroes the array in a `finally` (existing code, unchanged). Absent / empty
  password → `400`. Policy checks (min 8 bytes, ≤ 72 bytes, not all one character, does
  not contain the email local-part) are `UserService.validatePwd`, already in place, and
  raise `BadRequestException` (`400`).
- **Self-reference.** Not applicable to create (the caller is not the new row).
- **Idempotency.** None — this is a create. A repeated submit with the same email is a
  `409`.
- **Not covered:** deactivated-account login enforcement, `verifyLogin` changes. All
  deferred. (CSV bulk rows without a password are now built — see
  `prompts/features/bulk-employee-import.md`.)

## REST API

| Method | Path | Body (`*Form`) | Success response | Auth |
|---|---|---|---|---|
| POST | `/api/hr/employees` | `EmpForm` | `200`, `{ "id": <newUserId> }` | authenticated; role `ORG_ADMIN` or `HR` |

The `{ "id": n }` body is a literal `Map.of("id", id)` in the controller, matching
`AuthController`'s `Map.of(...)` style for trivial bodies (the frontend ignores the
response body on success). No `*Data` class is introduced for this pass; a reviewer who
wants one would add `model/data/CreatedData.java` — called out, not done.

Errors flow through `GlobalExceptionHandler` unchanged: `BadRequestException` → `400`,
`ConflictException` → `409`, `AccessDeniedException` → `403`,
`HttpMessageNotReadableException` (bad enum-ish string, malformed JSON) → `400`.

### `EmpForm` (request) — `model/form/EmpForm.java`

Mirrors `hr-employees.js`'s create payload, minus `forcePasswordReset`:

```
employeeCode   String        // generated EMP-<8 hex> if blank
name           String        // nullable
email          String        // required
phone          String        // nullable
dateOfJoining  LocalDate     // "YYYY-MM-DD"; today if absent
dateOfExit     LocalDate     // nullable
employeeType   String        // "REGULAR" if absent
designation    String        // nullable
department     String        // nullable
managerId      Long          // null = none; else must be a user in the caller's org
basicSalary    BigDecimal    // nullable
role           String        // required: "EMPLOYEE" | "HR"
password       byte[]        // required; @JsonDeserialize(PwdDeserializer.class)
status         String        // accepted, ignored (always created ACTIVE)
```

`@Getter @Setter`, `@JsonIgnoreProperties(ignoreUnknown = true)`.

## Workflow

`HrEmpFlow` (`workflow`), `@Transactional(rollbackFor = Exception.class)` at the
class level (its only method writes). Orchestrates `UserService` + `EmpService`;
holds **no** authorization logic and never reads `UserHelper`.

**`create(Long orgId, EmpForm form) -> Long`**

1. Normalize/validate the form:
   - `email` non-blank → else `BadRequestException`.
   - `role` ∈ {`EMPLOYEE`, `HR`} → else `BadRequestException`. (The
     `ORG_ADMIN`-in-payload and `HR`-by-an-`HR` rules are the controller's;
     the Flow only re-checks the value is one it will write.)
   - `password` length > 0 → else `BadRequestException`.
2. `409` pre-check: `userSvc.existsInOrgByEmail(orgId, email)` → `ConflictException`.
3. `managerId` (if non-null): `userSvc.existsInOrg(orgId, managerId)` → else
   `BadRequestException`.
4. `UserPojo user = userSvc.createUser(orgId, email.trim(), form.getPassword(), role)` —
   existing method; already sets `status = ACTIVE`, `is_active = true`,
   `emailVerified = false`, `failedAttempts = 0`, timestamps; validates and zeroes the
   password.
5. Build the `EmpPojo`: `userId = user.getId()`, `organizationId = orgId`,
   `employeeCode` (form or generated), `name`, `phone`, `dateOfJoining` (form or today),
   `dateOfExit`, `employeeType` (form or `"REGULAR"`), `designation`, `department`,
   `managerId`, `basicSalary`. `address` is not set (the self-service
   `PATCH /api/employees/me` owns it) — stays `null`.
6. `empSvc.createProfile(pojo)` — sets `createdAt`/`updatedAt` and saves.
7. Return `user.getId()`.

`employeeCode` generation is a `Flow`-private helper: `"EMP-" + 8 hex chars` from
`java.util.UUID.randomUUID()` (matches `EmpService.createOrgAdminProfile`'s existing
`"ADMIN-" + UUID…substring(0,8)` style).

## Business Logic

### `AbstractController` — one new guard

- **`requireAnyRole(String... roles)`** — `403` (`AccessDeniedException`, mapped by
  `GlobalExceptionHandler`) unless the principal's `roles` contains at least one of the
  arguments. Generalizes the existing single-value `requireRole`; same null-principal
  handling.

The `ORG_ADMIN`-in-payload → `400` and `HR`-creating-`HR` → `403` checks are
plain `if` statements in the controller method (they depend on the request body, not
just the principal), throwing `BadRequestException` / `AccessDeniedException`.

### `UserService` — two thin read helpers (auth table only, stays in its lane)

- `boolean existsInOrgByEmail(Long orgId, String email)` — delegates to
  `userRepo.existsByOrganizationIdAndEmail`.
- `boolean existsInOrg(Long orgId, Long userId)` — delegates to
  `userRepo.existsByIdAndOrganizationId`. Used for the `managerId` check.

`createUser(Long, String, byte[], String role)` is **unchanged** — it already accepts
the role string, validates and hashes the password, and sets all the lifecycle columns.

### `EmpService` — one new writer

- `EmpPojo createProfile(EmpPojo emp)` — sets `createdAt = updatedAt =
  Instant.now()`, `empRepo.save(emp)`, logs `employee profile created userId=…`. Sibling
  of the existing `createOrgAdminProfile`, which stays for the signup path.

## POJOs

**No new columns, no new entities, no field-type changes.** `role`, `status`, and
`employeeType` stay `String` — converting the fixed value sets to enums (per
`jpa-persistence.md` §7 / `coding.md` §14) is a real improvement but touches ~10 shipped
files and all their tests; it is **deferred** as its own change, not smuggled into a
single-endpoint feature. This endpoint writes only the exact constant strings the
columns already hold (`"EMPLOYEE"`, `"HR"`, `"ACTIVE"`, `"REGULAR"`).

`user.is_active` is set to `true` by `UserService.createUser` (already the case — it
calls `user.setActive(true)` explicitly, per the Hibernate "DEFAULT 1 doesn't apply to
JPA inserts" trap noted in `prompts/schema.md`).

## Database Indexes

**None added.** New query paths and what covers them:

- `UserRepo.existsByOrganizationIdAndEmail(Long, String)` — leftmost-prefix + full match
  on the existing `uk_user_org_email (organization_id, email)` unique index.
- `UserRepo.existsByIdAndOrganizationId(Long, Long)` — primary key.
- `EmpService.createProfile` inserts only; `uk_employee_org_code` already enforces
  code uniqueness.

## Database

`UserRepo` — add:
- `boolean existsByOrganizationIdAndEmail(Long organizationId, String email)`
- `boolean existsByIdAndOrganizationId(Long id, Long organizationId)`

`EmpRepo` — no change (`save` is inherited).

## External Libraries

None. `java.util.UUID` for the code; `BCryptPasswordEncoder` already wired in
`UserService`.

## Configuration Parameters

None.

## Relevant Files

**New:**
- `src/main/java/com/leaveapp/rest/HrEmpController.java` — `POST /api/hr/employees`;
  `requireAnyRole("ORG_ADMIN", "HR")` + the two body-dependent role checks;
  request-entry / completion logging; returns `Map.of("id", id)`.
- `src/main/java/com/leaveapp/workflow/HrEmpFlow.java` — validate/normalize the
  form, apply defaults, `409` + `managerId` checks, orchestrate
  `UserService.createUser` + `EmpService.createProfile`.
- `src/main/java/com/leaveapp/model/form/EmpForm.java` — request body.
- `src/test/java/com/leaveapp/rest/HrEmpControllerTest.java` — plain unit test with
  a hand-written `HrEmpFlow` stub (no Mockito), modelled on `OrgControllerTest`:
  role gate (`403` for `EMPLOYEE`, missing principal), `ORG_ADMIN`-in-payload → `400`,
  `HR`-creating-`HR` → `403`, happy path passes the principal's `orgId` +
  the form through and returns `{id}`.
- `src/test/java/com/leaveapp/workflow/HrEmpFlowTest.java` — extends
  `RepositoryTestSupport` (H2), modelled on `AuthFlowTest`: creates an org + caller,
  then asserts a successful create writes both a `user` (role, `ACTIVE`, `is_active`,
  hashed password that `verifyLogin` accepts) and an `employee` row (org-scoped, code,
  defaults); duplicate email → `ConflictException`; unknown/cross-org `managerId` →
  `BadRequestException`; blank `role` / blank `password` → `BadRequestException`;
  `dateOfJoining` / `employeeType` defaulting.

**Modified:**
- `src/main/java/com/leaveapp/rest/AbstractController.java` — add `requireAnyRole`.
- `src/main/java/com/leaveapp/service/UserService.java` — add `existsInOrgByEmail`,
  `existsInOrg`.
- `src/main/java/com/leaveapp/service/EmpService.java` — add `createProfile`.
- `src/main/java/com/leaveapp/repo/UserRepo.java` — add the two `exists…` finders.
- `prompts/api-endpoints.md` — move the `POST /api/hr/employees` row from "Planned,
  unconfirmed" to "Built" with the real shape; leave the other `/hr/*` rows as planned.
- `prompts/schema.md` — `user` note: `role` is now written as `HR` / `EMPLOYEE` by
  a real endpoint. No DDL change.
- `prompts/features/multi-tenancy.md` — Progress note pointing here for the
  "HR/ORG-admin creates a single employee" slice.
- `CLAUDE.md` — "What's built so far": add this endpoint.

## Deferred

Explicitly **not** in this pass — each is its own future change:

- `GET /api/hr/employees/{id}` (get-by-id). (`GET` list, `PATCH /{id}` update, and
  `DELETE /{id}` soft-delete — built 2026-08-31.)
- `PATCH /api/hr/employees/{id}/reactivate` — undo the soft-delete. The `PATCH /{id}`
  update endpoint deliberately leaves `is_active` untouched so it doesn't fight the
  `DELETE` / reactivate pair over that column.
- `POST /api/hr/employees/reassign-manager` (bulk `employee.manager_id`).
- `String` → enum conversion for `role` / `status` / `employeeType` (`pojo/Role`,
  `pojo/UserStatus`, `pojo/EmployeeType`), and the ripple through `JwtService`,
  `AuthFlow`, `EmpFlow.toData`, `UserService.verifyLogin`, and every affected test.
- `UserService.verifyLogin` gaining a `!user.isActive()` guard so a soft-deleted account
  cannot log in.
- Hibernate tenant `@Filter` (scoping stays explicit `organizationId` params).
- Manager-cycle detection on `managerId`.
- `OrgPojo.status` enum conversion.
