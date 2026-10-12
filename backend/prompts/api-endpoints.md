# API Endpoints

Every endpoint this backend exposes or plans to expose, grouped by category, with a
build status. **Built** = real code exists and is covered by tests today. **Planned** =
specified in `prompts/features/multi-tenancy.md` but no code exists yet; shapes marked
"assumed" there are not yet confirmed with the user. This file is a index/checklist,
not a spec -- see the linked feature docs for full detail (validation rules, edge
cases, business logic).

All routes are cookie-authenticated (`HttpOnly` JWT cookie, not a bearer token) except
where noted `public`.

## Auth & Session

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | POST | `/api/auth/signup` | `{orgKey, email, password}` -- `orgName`/`name` deliberately not collected (less friction); set later via `PATCH /api/organization` | `{}`, JWT cookie set | public |
| Built | POST | `/api/auth/login` | `{orgKey, email, password}` | `{}`, JWT cookie set | public |
| Built | GET | `/api/auth/me` | -- | `SessionData` (see below) | cookie |
| Built | POST | `/api/auth/logout` | -- | `{}`, clears cookie | none required (idempotent) |
| Built | GET | `/api/auth/verify-email` | `?token=` (signed 24h token) | `302` to `app.verify.redirect-base?verified=1\|0` | public (token is the authority) |
| Built | POST | `/api/auth/resend-verification` | -- | `{}` | cookie |
| Built | POST | `/api/auth/forgot-password` | `{orgKey, email}` | `200 {}` -- always (no account enumeration) | public |
| Built | POST | `/api/auth/reset-password` | `{token, newPassword}` (signed 30-min token from the emailed link) | `200 {}`; `400 {summary}` on bad/expired token or weak password | public (token is the authority) |
| Built | POST | `/api/auth/set-password` | `{token, newPassword}` (signed 7-day invite token). Single-use: only an `INVITED` user; `INVITED` -> `PENDING` | `200 {}`, JWT cookie set (onboarding-only session -- see Onboarding below); `400` bad/expired/used token or weak password | public (token is the authority) |

**Updated 2026-09-30:** `login` answers the same generic failure for `INVITED` users (no
password yet); a `PENDING` user logs in with an onboarding-only session; the first login of
an `ONBOARDED` user makes them `ACTIVE`. A `forgot-password` reset on an `INVITED` user moves
them to `PENDING`. See `prompts/features/employee-onboarding.md`.

`resend-verification` re-mails the verification link to the caller's own address; `400`
if already verified or if called again within `app.verify.resend-cooldown-seconds`
(default 60). See `prompts/features/employee-auth-and-profile.md`.

**Removed (2026-09-17)**: `POST /api/auth/change-password` (`ChangePwdForm`,
`UserService.changePassword`). No live frontend page called it (only stale `target/`
build artifacts referenced it) -- a password change now goes entirely through the
forgot-password flow (`POST /api/auth/forgot-password` + `/reset-password`), which is
already built and tested. Confirmed with the user.

`forgot-password` + `reset-password` are the self-service password reset flow: request a
link, then set a new password from it. `forgot-password` mails a link to the **frontend**
`reset-password.html?token=` (config `app.reset.link-base`) only for a real, usable
account, throttled per `(orgKey, email)` by `app.reset.request-cooldown-seconds`
(default 60); the response is an identical `200 {}` either way. The token is a stateless
purpose-scoped JWT (30-min TTL, not single-use), the same mechanism as `verify-email`.
`reset-password` also clears account lockout on success and does **not** issue a session
(the user logs in afterwards). This is distinct from the invite `set-password` link (built,
single-use, issues an onboarding-only session). See `prompts/features/forgot-password.md`.

**`SessionData` shape** (`GET /auth/me`, `GET /employees/me`):
`id, employeeCode, name, email, phone, address, role, managerId, department,
designation, employeeType, dateOfJoining, dateOfExit, basicSalary, status, isManager,
managerName, organizationName`. (`forcePasswordReset` was removed -- see
`prompts/features/multi-tenancy.md`. `directoryProfileComplete` was removed 2026-09-30 --
gate on `status` instead.) `GET /auth/me` is also allowed for the onboarding-only
(`PENDING`) session.

## Employee Self-Service

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | GET | `/api/employees/me` | -- | `SessionData` (identical shape to `/auth/me`) | cookie |
| Built | PATCH | `/api/employees/me` | `{phone, address}` | `SessionData` | cookie |

## Org Settings

`ORG_ADMIN` only. The org is taken from the JWT, not the URL -- a caller can only
ever read/edit their own org. See `OrgService.update` for validation.

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | GET | `/api/organization` | -- | `OrgData` (see below) | `ORG_ADMIN` |
| Built | PATCH | `/api/organization` | `{name?, hrNotifyEmail?}` -- omitted field = unchanged; blank `name` clears it; `hrNotifyEmail` if sent must be non-blank and contain `@` (else 400) | `OrgData` | `ORG_ADMIN` |

**`OrgData` shape**: `id, name, orgKey, hrNotifyEmail, status`. `orgKey`, `status`,
`id` are read-only; only `name` and `hrNotifyEmail` are editable.

## HR / Org Admin -- Employee Management

Role hierarchy: `ORG_ADMIN` (created only by signup) is the only role that creates users
(`HR` and `EMPLOYEE`, via invite -- see "Admin -- User Invites" below); `HR` lists, edits and
soft-deletes `EMPLOYEE` rows (never creates). `organizationId` is always the JWT
claim. See `prompts/features/hr-employee-management.md`.

**Updated 2026-09-30:** `POST /api/hr/employees` was removed (creation is
`POST /api/admin/users`), `password` is no longer accepted anywhere on this resource (a user
sets their own from the invite or forgot-password mail), and import is `ORG_ADMIN` only.

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | GET | `/api/hr/employees` | -- | `200`, `EmpRowData[]` -- `{id, employeeCode, name, email, phone, role, status, managerId, department, designation, employeeType, dateOfJoining, dateOfExit, basicSalary}` for every account in the caller's org; unpaged, unfiltered (the HR screen filters client-side) | authenticated; `ORG_ADMIN` or `HR` |
| Removed 2026-09-30 | POST | `/api/hr/employees` | -- | replaced by `POST /api/admin/users` | -- |
| Built | PATCH | `/api/hr/employees/{id}` | `EmpForm`, every field optional -- `null` (or blank, for frontend-blanked strings) = unchanged: `{email, role, employeeCode, name, phone, dateOfJoining, dateOfExit, employeeType, designation, department, managerId, basicSalary, status}`; **no `password`**. `status` (`ACTIVE`/`ON_NOTICE`/`EXITED`) cannot be changed for an `INVITED`/`PENDING`/`ONBOARDED` user except to `EXITED` (`400`). `role`: `ORG_ADMIN` in payload -> `400`, `HR` needs an `ORG_ADMIN` caller (`403`). Nullable columns (`dateOfExit`, `managerId`, `basicSalary`) can't be *cleared* via PATCH | `200`, `EmpRowData` | authenticated; `ORG_ADMIN` or `HR` (an `HR` may only modify `EMPLOYEE` rows) |
| Built | PATCH | `/api/hr/employees/{id}/details` | `EmpDetailsForm` -- `{phone, personalInfo{dateOfBirth, gender, nationality, maritalStatus, bloodGroup, personalEmail}, address{address1, address2, city, landmark, postalCode, state, country}, emergencyContact{emergencyName, emergencyRelationship, emergencyPhone}, payIds{bankAccount, bankIfsc, nationalId, taxId, passport}}`, every section/field optional (`null` = unchanged); unknown properties `400`; field rules as onboarding. Audited by section name (`audit_log`, never values) | `200 {}` | authenticated; `ORG_ADMIN` or `HR` (an `HR` may only edit `EMPLOYEE` rows) |
| Built | DELETE | `/api/hr/employees/{id}` | -- | `200 {}` | authenticated; `ORG_ADMIN` or `HR` (an `HR` may only deactivate `EMPLOYEE` rows) |
| Built | POST | `/api/hr/employees/import` | `EmpImportForm` -- `{rows: [{name*, email*, role* (EMPLOYEE\|HR), employeeCode, phone, dateOfJoining, dateOfExit, employeeType, designation, department, managerEmail, basicSalary}]}`; only name/email/role are mandatory; no `password` | `200`, `EmpImportResultData` -- `{created, skipped: string[]}`; each created user is `INVITED` and gets an invite mail | authenticated; `ORG_ADMIN` only. See `prompts/features/bulk-employee-import.md` |

Creation (user + 1:1 `employee` + empty `employee_sensitive` rows in one transaction) is
`POST /api/admin/users` / import, both `INVITED`; a password is never supplied. `GET` zips `user` + `employee` rows for the org in two queries
(no per-row manager/org lookups). `PATCH` mutates the managed entities in one
transaction; a cross-org or unknown `{id}` is a `404` (no cross-org existence leak);
`PATCH` does **not** touch `user.is_active`. `DELETE` is a soft-delete
(`is_active=false`, `status=EXITED`, `date_of_exit=today`) -- no row is ever hard-deleted
(leave history keeps its FKs); it is idempotent (already-`EXITED` -> no-op `200`,
original exit date kept) and refuses to deactivate the caller's own account (`400`).
`EXITED` already blocks login via `CredentialService.verifyLogin`. get-by-id / reactivate /
reassign-manager are still deferred -- see the feature doc's Deferred section.

## Admin -- User Invites

`ORG_ADMIN` only; org from the JWT. Bodies reject unknown properties (`400`). See
`prompts/features/employee-onboarding.md`.

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | POST | `/api/admin/users` | `UserInviteForm` -- `{name*, email*, role* (EMPLOYEE\|HR)}` | `200 {id}`; user is `INVITED`, invite mail queued; `400` validation, `409` duplicate email or seat limit | `ORG_ADMIN` |
| Built | POST | `/api/admin/users/{id}/resend-invite` | -- | `200 {}`; `400` if the user is not `INVITED`, `404` unknown/cross-org | `ORG_ADMIN` |

## Onboarding (the invited user's own form)

Only the onboarding-only session of a `PENDING` user (`ROLE_ONBOARDING`) reaches these; that
session reaches nothing else except `GET /api/auth/me` and logout. Always the caller's own
row. Bodies reject unknown properties (`400`); field rules in
`prompts/features/employee-onboarding.md`.

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | GET | `/api/onboarding` | -- | `OnboardingData` -- saved entries (unmasked, own data), `personalDone`/`emergencyDone`/`payIdsDone`, `nextStep` (`personal`\|`emergency`\|`payIds`\|`review`); also the preview | `ROLE_ONBOARDING` |
| Built | PUT | `/api/onboarding/personal` | `{phone*, dateOfBirth*, gender*, nationality*, maritalStatus*, personalEmail*, bloodGroup, address1*, address2, city*, landmark, postalCode*, state*, country*}` flat -- personal details **and** address | `OnboardingData` | `ROLE_ONBOARDING` |
| Built | PUT | `/api/onboarding/emergency` | `{emergencyName*, emergencyRelationship*, emergencyPhone*}` | `OnboardingData` | `ROLE_ONBOARDING` |
| Built | PUT | `/api/onboarding/pay-ids` | `{bankAccount*, bankIfsc*, nationalId*, taxId*, passport}` | `OnboardingData` | `ROLE_ONBOARDING` |
| Built | POST | `/api/onboarding/submit` | -- | `200 {}`; `PENDING` -> `ONBOARDED`, session revoked (frontend goes to login), HR + org admins notified; `400` if a step is incomplete | `ROLE_ONBOARDING` |

(`*` = mandatory. There is no `/api/onboarding/address`.)

| Status | Method | Path | Body | Response | Auth |
|---|---|---|---|---|---|
| Built | GET | `/api/directory` | -- (query: `department`, `managerId` [`ORG` scope only, else `403`], `search`, `page`, `size` [default 0/50, max 200]) | `200`, `Page<DirectoryData>` -- `{id, employeeCode, name, email, phone, designation, department, employeeType, status, dateOfJoining, managerId, workLocation}`; scope resolved server-side (`ORG_ADMIN`/`HR` -> whole org, a caller with reports -> just those, else -> self); `EXITED` accounts excluded | any authenticated session |
| Removed 2026-09-30 | POST / PATCH / DELETE | `/api/directory[/{id}]` | -- | replaced by `POST /api/admin/users`, `PATCH /api/hr/employees/{id}[/details]`, `DELETE /api/hr/employees/{id}` | -- |

**Updated 2026-09-30:** the directory is read only. For `ORG_ADMIN` **and `HR`** callers `GET`
also returns the sensitive block (`personalInfo`, `address`, `emergencyContact`, masked
`bankAccount`/`nationalId`/`taxId`/`passport`, `bankIfsc`) and `basicSalary`; managers and
employees never receive them.

## Platform Administration

**Does not exist in this design.** A `platform_admin` table/service was built, then
deleted per an explicit user decision -- self-service signup is the only way an
organization is created; there is no ops-only superadmin surface. See
`prompts/features/multi-tenancy.md`'s Correction history if this changes.

## Leave Domain

Built. `LeaveController` (`/api`), `ManagerLeaveController` (`/api/manager`),
`HrLeaveController` (`/api/hr`) over `LeaveFlow`/`ManagerLeaveFlow`/`HrLeaveFlow`
and `LeaveType`/`LeaveRequest`/`LeaveBalance`/`PublicHoliday` services. Shapes per
`leave-frontend/fronted/API-REQUIREMENTS.md`. Covered by `LeaveFlowTest`.

| Status | Method | Path | Notes |
|---|---|---|---|
| Built | GET | `/api/leave-types` | active types applicable to the caller's `employeeType` |
| Built | GET | `/api/leave-balances/me` | `[{leaveTypeId, leaveTypeCode, leaveTypeName, balance}]`; balance = SUM(ledger) for the current calendar year, annual entitlement credited pro-rata by joining month on first read |
| Built | POST | `/api/leave-requests` | apply -> `{id, status}`; validates dates, advance notice, situationType, non-overlap; builds the approval chain (config steps, else one MANAGER step) |
| Built | GET | `/api/leave-requests/me` | `?recent=true` -> latest 3; summary rows |
| Built | GET | `/api/leave-requests/{id}` | full detail incl. `approvals[]`; visible to owner, their manager, or org HR/admin (else 404) |
| Built | POST | `/api/leave-requests/{id}/action` | `{action: APPROVED\|REJECTED, comments?}`; one endpoint for manager + HR -- who may act is resolved from the current pending step |
| Built | POST | `/api/leave-requests/{id}/cancel` | owner only, while `APPLIED`/`PENDING_MANAGER`/`PENDING_HR` |
| Built | GET | `/api/manager/team` | direct reports + embedded `balances[]` |
| Built | GET | `/api/manager/leave-requests` | reports' requests, unfiltered |
| Built | GET | `/api/hr/leave-requests` | company-wide (caller's org), unfiltered |
| Built | GET/POST/PATCH/DELETE | `/api/hr/leave-types[/{id}]` | catalog CRUD; DELETE is soft (`is_active=false`); `ORG_ADMIN`/`HR` |
| Built | GET | `/api/hr/holidays` | `[{id, name, date, year}]` for the current year |
| Deferred | -- | LOP impose, HR override, holiday create/delete, lop-watchlist, audit-log | unwired in the frontend -- see `prompts/TODO.md` |

**Removed (2026-09-17)**: `nonNegotiable` on `HolidayForm`/`HolidayData`/`PublicHolidayPojo`
(the "National" vs. "Other compulsory" split). The `is_non_negotiable` column itself
still exists in any already-provisioned database -- `hbm2ddl.auto=update` never drops
columns, so dropping it is a manual step: `ALTER TABLE public_holiday DROP COLUMN
is_non_negotiable;`, run by the engineer against each environment that needs it.

`notification`, `audit_log` and exit settlement remain schema-only. Approval
mechanics: `leave_request.current_step_order` walks `leave_request_approval` rows
(one per step, mirrored from `leave_type_approval_step` at apply time); reaching the
last step writes a `DEBIT` `leave_ledger_entry`. A MANAGER step for an employee
with no manager auto-approves (would otherwise deadlock).

## Not part of this backend at all (yet)

One-on-Ones, Appraisals, and HR admin CRUD beyond employees (leave types, holidays)
are listed in `leave-frontend/fronted/API-REQUIREMENTS.md` but have no corresponding
work started here -- see `prompts/TODO.md`.
