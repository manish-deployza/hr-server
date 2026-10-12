# Bulk Employee Import

> **Updated 2026-09-30 -- read this first; it overrides the sections below where they disagree.**
> - **`ORG_ADMIN` only** (`@PreAuthorize("hasRole('ORG_ADMIN')")` on the import method). HR can no
>   longer import; the old "HR caller cannot grant HR" per-row rule and the `callerIsOrgAdmin`
>   parameter are gone.
> - **Mandatory columns per row: `name`, `email`, `role` (`EMPLOYEE`|`HR`).** A row missing one is
>   skipped with a reason (`row N (email): name is required`) and the other rows are still created.
>   Every other column is optional (`employeeCode`, `phone`, `dateOfJoining`, `dateOfExit`,
>   `employeeType`, `designation`, `department`, `managerEmail`, `basicSalary`); the shape/length rules in
>   `workflow/FieldRules` are applied to email, name, phone, employeeCode, employeeType, designation
>   and department (a violation skips the row). `name` is trimmed.
> - **No temp passwords, no credentials.** `HrImportSupport.genTempPassword`, `ImportCredentialData`
>   and `credentials` in the response are deleted. Each created row is an `INVITED` user with no
>   password and gets the same invite mail as `POST /api/admin/users` (link -> set password ->
>   onboarding form). The response is `{created, skipped[]}` only. `INVITED` users cannot log in, so
>   nothing about the file is usable as a credential.
> - Everything else (row cap `app.import.max-rows`, best-effort per-row skips, seat-limit skips,
>   duplicate-within-file / existing-email skips, `managerEmail` resolution, all-or-nothing phase 2
>   on a race) is unchanged. The "Passwords", "Temp password lifetime", "Generated password vs
>   policy" and credentials response text below is historical.

## Status: Built

Implemented as designed below, with one deviation from the original file placement:
`HrEmpFlow` was already at 197 lines, and the full `importEmployees` orchestration
plus its helpers would have pushed it well past the 200-line ceiling. Split instead into:

- **`EmpImportFlow`** (new `@Service`) — owns `importEmployees` (phase 1 loop,
  phase 2 loop, result assembly). Calls `HrEmpFlow.persistRow` (below) for the
  actual insert, so there is exactly one place that writes the `user` + `employee` pair.
- **`HrEmpFlow`** — `create` unchanged in behaviour, refactored into
  `validate…(); return persistRow(orgId, form);`. `persistRow` is package-private
  (not `private`) so `EmpImportFlow` can call it.
- **`HrImportSupport`** (new, package-private, static methods only, sibling to the
  existing `HrEmpMapper`) — `resolveRow` (phase-1 per-row validation),
  `genTempPassword`, `skipLine`, `toEmployeeForm`. No DB writes; read-only lookups via
  a passed-in `UserService`.

Everything else below — endpoint shape, validation rules, edge cases, config — was
built exactly as specified.

Adds a single endpoint that creates many employee accounts in one call, from rows an
admin uploaded as a CSV/TSV file. Extends the employee-management feature
(`hr-employee-management.md`); the single-create path (`POST /api/hr/employees`) is
unchanged.

## Overview

`POST /api/hr/employees/import` — body is `{ "rows": [ {…}, … ] }`, **JSON, not a file**.
The browser already parses the `.csv`/`.tsv` file client-side (`parse-delimited.js`,
wired to `#bulk-upload-modal` in `hr-employees.html`) into an array of row objects keyed
by header; this endpoint takes that array. No file upload, no multipart, no CSV parser on
the server — matching the organisation's existing import pattern
(`POST /hr/appraisal-cycles/{id}/roster-import`).

Each row becomes a `user` + 1:1 `employee` pair, exactly like single-create. Per the
approved decisions:

- **Passwords:** the file carries none. The server generates a random temp password per
  created row and returns `{email, tempPassword}` for each one, **once**, in the
  response. The admin distributes them.
- **Failure mode:** best-effort. Every valid row is created; invalid rows are reported,
  not fatal. The response lists what was skipped and why.
- **Manager reference:** a row names its manager by `managerEmail`, resolved to a user in
  the caller's org. Unknown email → that row is skipped.
- **Verification email:** one per created row (`VerificationMailer.send`, best-effort,
  failures swallowed) — same as single-create.

> **Touches authentication** (creates credentials, generates and returns passwords). Per
> the security baseline's Scope note, flag it for a second reviewer at
> `/deployza-code:review`.

## Edge Cases

- **Authorization.** `ORG_ADMIN` or `HR`, enforced in the controller
  (`requireAnyRole`), org from the JWT — never a body field. A row requesting
  `role = HR` when the caller is only `HR`, or `role = ORG_ADMIN` from
  anyone, is **skipped with a reason** (not a whole-batch failure) — the per-row
  equivalent of `HrEmpController.checkPayloadRole`.
- **Two-phase, mostly-best-effort.** Phase 1 validates every row (email present & well-
  formed, role writable, not a duplicate *within the file*, email not already in the org,
  `managerEmail` resolves) and collects skip reasons. Phase 2 inserts the rows that
  passed, inside one transaction. A phase-2 failure is only possible via a race (e.g. the
  same email inserted by a concurrent request between phase 1's check and phase 2's
  insert); if it happens the batch rolls back and nothing is created — the admin
  re-uploads. Every *predictable* rejection is already per-row by then. This is the one
  non-best-effort path and is called out deliberately.
- **Idempotency.** Not idempotent. Re-running creates new accounts for rows whose email
  still doesn't exist; rows whose email now exists are skipped ("an account with this
  email already exists"). Re-uploading a file after a partial success is therefore safe —
  the already-created rows skip as duplicates.
- **Manager ordering.** Single pass, no forward references: a row's `managerEmail` must
  point at someone who already exists in the org **or** appears earlier in `rows` and was
  created in phase 2 before… — no. Phase 2 inserts after phase 1 validates, so
  `managerEmail` must resolve to a **pre-existing** user at phase-1 time. A manager and
  their report cannot be created in the same file. Documented limitation; matches the
  current frontend behaviour (it only resolves managers against already-loaded
  employees).
- **Duplicate email within the file.** First occurrence is processed; every later row
  with the same (trimmed) email is skipped ("duplicate email within the file").
- **Row cap.** `rows` empty/null → `400` ("no rows provided"). More than
  `app.import.max-rows` (default 500) → `400` before any row is touched.
- **Temp password lifetime.** Generated as a `byte[]`, passed to the existing
  `UserService.createUser` which hashes and zeroes it. A `String` copy is produced *only*
  for the response DTO (`tempPassword` must be shown to the admin once) and is never
  persisted — only the BCrypt hash reaches the database. This is the documented
  "returned once" pattern (`api-endpoints.md`, and the invite-token precedent in
  `multi-tenancy.md`). Flagged for security review above.
- **Generated password vs policy.** 12 chars from an unambiguous alphabet
  (`A–Z a–z 2–9`, no `0 O 1 l I`), `SecureRandom`. It satisfies `UserService.validatePwd`
  by construction (length ≥ 8, not all one character); on the astronomically unlikely
  chance it contains the email's local-part, `genTempPassword` regenerates (up to 3
  attempts) so one unlucky draw can't roll back the batch.
- **Mail burst.** One verification email per created row. A 500-row import is up to 500
  Mailjet sends; each is best-effort and a failure is swallowed (`VerificationMailer`),
  so it never affects the result.
- **Concurrency.** No extra locking. `uk_user_org_email` is the backstop; a concurrent
  duplicate surfaces as the phase-2 race above.

## API / Interface

`POST /api/hr/employees/import` — auth: `ORG_ADMIN` or `HR`.

**Request** — `EmpImportForm`:

```json
{ "rows": [
  { "email": "alice.roy@acme.test", "role": "EMPLOYEE", "name": "Alice Roy",
    "employeeCode": "EMP-1001", "phone": "+91-90000-00001",
    "dateOfJoining": "2026-01-15", "dateOfExit": null,
    "employeeType": "REGULAR", "designation": "Backend Engineer",
    "department": "Engineering", "managerEmail": "carol.das@acme.test",
    "basicSalary": 72000.00 }
] }
```

Per row (`EmpImportRowForm`, `@JsonIgnoreProperties(ignoreUnknown = true)`):

| Field | Required | Notes |
|---|---|---|
| `email` | yes | trimmed, used as-is (no case-fold — consistent with `create`/`login`) |
| `role` | yes | `EMPLOYEE` \| `HR` (`HR` only if caller is `ORG_ADMIN`) |
| `employeeCode` | no | blank → generated `EMP-<8 hex>` |
| `name`, `phone`, `designation`, `department` | no | free text |
| `dateOfJoining` | no | `YYYY-MM-DD`; absent → today |
| `dateOfExit` | no | `YYYY-MM-DD` |
| `employeeType` | no | absent → `REGULAR` |
| `basicSalary` | no | decimal |
| `managerEmail` | no | resolved to a user in the caller's org; unknown → row skipped |

No `password` field. A `managerId` field, if sent, is ignored (`ignoreUnknown`).

**Response** `200` — `EmpImportResultData` (shape mirrors `roster-import`):

```json
{
  "created": 2,
  "skipped": [
    "row 3 (bob@acme.test): an account with this email already exists",
    "row 5 (): email is required"
  ],
  "credentials": [
    { "row": 1, "email": "alice.roy@acme.test", "tempPassword": "Xk7pQ2m9dLrT" },
    { "row": 2, "email": "carol.das@acme.test", "tempPassword": "9mFhced2WkZp" }
  ]
}
```

`row` is the 1-based index into the submitted `rows` array. `skipped` entries are
pre-formatted strings (the frontend joins them). `credentials` is only the rows actually
created.

Error responses: `400` (no rows / over cap), `401` (no session), `403` (not
`ORG_ADMIN`/`HR`), `409`/`500` (phase-2 race — batch rolled back).

## Validation & Access Control

- `HrEmpController.importEmployees`: `requireAnyRole("ORG_ADMIN", "HR")`,
  `requireOrgId()`, entry/exit logging (`orgId`, `rows` count on entry; `created`,
  `skipped` count on exit — never a password or an email list).
- `SecurityConfig`: no change — `/api/hr/**` already falls under
  `.anyRequest().authenticated()` and the role check is in the controller.
- Per-row validation lives in `HrImportSupport.resolveRow` (called from
  `EmpImportFlow` phase 1), reusing the same rules as `create`
  (`WRITABLE_ROLES`, `existsInOrgByEmail`, manager-resolves-in-org). The
  `HR`-can't-grant-`HR` / nobody-grants-`ORG_ADMIN` rule is applied per row
  from the `callerIsOrgAdmin` flag the controller passes (same flag `update`/`deactivate`
  already take).

## Business Logic

`EmpImportFlow` (new `@Service @Transactional`), `HrEmpFlow` (existing), and
`HrImportSupport` (new, package-private static helpers — see Status for why the logic
is split across three files instead of the one originally planned):

- **`EmpImportFlow.importEmployees(Long orgId, boolean callerIsOrgAdmin, List<EmpImportRowForm> rows) → EmpImportResultData`**
  1. Guard `rows` non-empty and `rows.size() <= app.import.max-rows`, else `BadRequestException`.
  2. **Phase 1** — loop with a 1-based counter and a `Set<String>` of seen emails. For
     each row, in a try/catch that turns any `RuntimeException` into a
     `"row N (email): <message>"` skip line (`HrImportSupport.skipLine`): call
     `HrImportSupport.resolveRow(userSvc, orgId, callerIsOrgAdmin, email, seenEmails, row)`,
     which requires & checks the trimmed `email` isn't blank or a repeat, validates the
     role (`EMPLOYEE`/`HR`, and the `HR`-grant-needs-org-admin rule), rejects
     if `userSvc.existsInOrgByEmail`, and resolves `managerEmail` via
     `userSvc.findByOrgAndEmailOrNull` (`BadRequestException` if a non-blank
     `managerEmail` doesn't resolve). Survivors go into a `pending` list (the `PendingRow`
     record) carrying the row, its 1-based index, the resolved `managerId`, and the
     trimmed email.
  3. **Phase 2** — for each `pending` row: `byte[] tmp = HrImportSupport.genTempPassword(email)`;
     decode `tmp` to a `String` for the `credentials` entry; build an `EmpForm` via
     `HrImportSupport.toEmployeeForm(row, email, managerId, tmp)`; call
     `hrEmpFlow.persistRow(orgId, form)` (the insert half of `create`, now package-private
     so this sibling class can call it); zero `tmp` in a `finally`. `persistRow` also
     calls `verificationMailer.send`.
  4. Return `{ created = pending.size(), skipped, credentials }`.
- **`HrEmpFlow.create` is refactored**: its body becomes
  `validate…(); return persistRow(orgId, form);` — no behaviour change to the single
  endpoint. `persistRow` is package-private (not `private`) so `EmpImportFlow` can
  reuse it — the one place that writes the `user` + `employee` pair.
- **`HrImportSupport.genTempPassword(String email)`** — package-private static. 12 chars
  from `ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789` via `SecureRandom`;
  regenerate (≤3 tries) if the lowercased result contains the email's local-part.
  Returns `byte[]`.
- No change to `UserService` / `EmpService` — `createUser`,
  `findByOrgAndEmailOrNull` (added for forgot-password), `existsInOrgByEmail`,
  `createProfile` all already exist and are reused.

## Data Models

New, in `com.leaveapp.model`:

- **`form/EmpImportForm`** — `List<EmpImportRowForm> rows`.
- **`form/EmpImportRowForm`** — `@JsonIgnoreProperties(ignoreUnknown = true)`;
  fields per the table above; `LocalDate` for the two dates, `BigDecimal` for salary,
  `String` for the rest. No password.
- **`data/EmpImportResultData`** — `int created`, `List<String> skipped`,
  `List<ImportCredentialData> credentials`.
- **`data/ImportCredentialData`** — `int row`, `String email`, `String tempPassword`.

All `@Getter @Setter`. No persistence entities.

## Data Models — Persistence

**None.** No new table, column, index, or constraint. Reuses `user` / `employee` and the
existing `uk_user_org_email` uniqueness. Schema-design step: **not applicable**. No Flyway
migration.

## Background Processing

None. (The verification emails are sent inline, best-effort, as in single-create.)

## External Libraries

None. File parsing is client-side (`parse-delimited.js`, already in the frontend);
`SecureRandom` is JDK. `internal-libraries` checked — not applicable (standalone project,
no `deployza-lib-*`).

## Configuration Parameters

Add to `src/main/resources/app.properties` **and** `src/test/resources/test.properties`:

| Property | Default | Purpose |
|---|---|---|
| `app.import.max-rows` | `500` | Max rows accepted in one `POST /api/hr/employees/import`; over this the whole request is `400`. |

## Relevant Files

- `rest/HrEmpController.java` — **modified**: added `importEmployees` handler +
  `@PostMapping("/import")`, `hasRole(ORG_ADMIN)` passed through as `callerIsOrgAdmin`.
- `workflow/HrEmpFlow.java` — **modified**: `create` refactored into
  `validate…(); return persistRow(orgId, form);`; `persistRow` made package-private.
- `workflow/EmpImportFlow.java` — **new**: `importEmployees` orchestration (phase 1
  validate, phase 2 persist via `HrEmpFlow.persistRow`, result assembly).
- `workflow/HrImportSupport.java` — **new**: `resolveRow`, `genTempPassword`,
  `skipLine`, `toEmployeeForm` — package-private static helpers, sibling to
  `HrEmpMapper`.
- `model/form/EmpImportForm.java` — **new**.
- `model/form/EmpImportRowForm.java` — **new**.
- `model/data/EmpImportResultData.java` — **new**.
- `model/data/ImportCredentialData.java` — **new**.
- `src/main/resources/app.properties` / `src/test/resources/test.properties` —
  **modified**: added `app.import.max-rows` (default `500`).
- `prompts/api-endpoints.md` — **modified**: replaced the "Planned, unconfirmed
  `POST /api/employees` (CSV row)" line with the real `POST /api/hr/employees/import`
  Built row + response shape.
- `prompts/features/hr-employee-management.md` — **modified**: the CSV/`tempPassword`
  references across the doc now point at this one instead of saying "deferred".
- `prompts/features/bulk-employee-import.md` — **new** (this document).
- `src/test/java/com/leaveapp/workflow/EmpImportFlowTest.java` — **new** (see
  Testing Notes): all-valid import returns credentials that actually log in; mixed file
  → per-row skip lines, valid rows still created; unknown `managerEmail` → skip;
  `managerEmail` of an existing user resolves; duplicate email within the file → second
  skipped; `HR` caller + `role=HR` row → skip, `EMPLOYEE` rows in the same
  file still created; empty `rows` → `400`; over `app.import.max-rows` → `400`;
  re-import of the same file → all skipped as duplicates, `created = 0`.

## Testing Notes

Phase 2 runs inside the flow method's transaction, which in `HrEmpFlowTest` joins
the test's rollback — so committed rows are cleaned up between tests and each test can
use the same org fixture. Tests assert on the returned `EmpImportResultData` plus a
fresh `userRepo`/`empRepo` read, and prove a returned `tempPassword` works by calling
`authFlow.login` (or `userSvc.verifyLogin`) with it. Mail send hits a refused port and is
swallowed, as in the existing auth/HR flow tests.
