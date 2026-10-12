c# Employee Onboarding

## Status: Built (2026-09-30)

An org admin invites a user (name, email, role). The user opens the emailed link, sets a
password, fills one onboarding form (personal details with address, emergency contact, pay
and IDs), previews it, and submits. From then on HR and the org admin see the details in the
employee screens and can edit them. This replaces the old model where HR/admin typed the whole
employee record at creation and the employee only set a password.

Replaces (all removed 2026-09-30): `POST /api/hr/employees`, the admin-set and generated temp
passwords, `POST/PATCH/DELETE /api/directory`, `POST /api/employees/me/directory-profile`
and the `directoryProfileComplete` flag.

## Actors

| Actor | Can |
|---|---|
| `ORG_ADMIN` | Invite users (`POST /api/admin/users`), resend an invite, bulk-invite (`POST /api/hr/employees/import`), plus everything HR can |
| `HR` | List, edit and soft-delete employees (never create), edit the details the user filled in, set reporting manager and employment fields |
| Invited user | Set a password from the invite link, complete the onboarding form, submit |

## Status lifecycle (`user.status`, `UserStatus`)

| Status | Set by | Can log in? | Session scope |
|---|---|---|---|
| `INVITED` | admin invite / bulk import (invite mail queued) | **No** -- has no password; login answers the same generic `Invalid credentials` | none |
| `PENDING` | `POST /api/auth/set-password` (invite link), or a forgot-password reset while still `INVITED` | Yes | `ROLE_ONBOARDING` only: `/api/onboarding/**`, `GET /api/auth/me`, logout. Every other endpoint is 403 |
| `ONBOARDED` | `POST /api/onboarding/submit` | Yes (submit revokes the session, so the frontend sends them to login) | full, per role |
| `ACTIVE` | first successful login after `ONBOARDED` | Yes | full, per role |
| `ON_NOTICE` | HR/admin `PATCH` | Yes | full |
| `EXITED` | `DELETE /api/hr/employees/{id}` (soft delete) or `PATCH status=EXITED` | No; existing sessions revoked | none |

Signup creates the org creator directly as `ACTIVE` (no onboarding).

## Flow

```
ORG_ADMIN  POST /api/admin/users {name, email, role}
   -> user INVITED (no password), employee row, empty employee_sensitive row, invite mail queued
        (link = app.invite.link-base?token=<7-day signed set-password token>)
User  opens link -> POST /api/auth/set-password {token, newPassword}
   -> INVITED -> PENDING, emailVerified=true, session cookie set (onboarding-only)
   -> frontend redirects to the onboarding form
User  GET /api/onboarding                -> saved entries + nextStep (resume point / preview)
      PUT /api/onboarding/personal       -> personal details + address
      PUT /api/onboarding/emergency
      PUT /api/onboarding/pay-ids
      GET /api/onboarding                -> preview (own data, unmasked); edit a step = PUT it again
      POST /api/onboarding/submit        -> PENDING -> ONBOARDED, session revoked, HR/admin notified
   -> frontend redirects to login; first login -> ACTIVE
HR/ORG_ADMIN  see the user in the employee screens; set manager/employment fields
      (PATCH /api/hr/employees/{id}) and edit details (PATCH /api/hr/employees/{id}/details)
```

A `PENDING` user who closes the tab logs in normally with their password and lands back in
the onboarding form (the session is onboarding-scoped, `nextStep` says where to resume). The
invite link only matters while `INVITED`; if it expired, the admin uses **resend invite**.

## REST API

All bodies below reject unknown JSON properties (`400`); the request objects are declared
flat (Jackson skips the unknown-property check on `@JsonUnwrapped`). Field rules are in
"Validation" below; a violation is always a `400` naming the field.

| Method | Path | Auth | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/api/admin/users` | `ORG_ADMIN` | `UserInviteForm` `{name*, email*, role* (EMPLOYEE\|HR)}` | `200 {id}` | `400` validation / role not EMPLOYEE or HR; `409` duplicate email in org, or seat limit reached |
| POST | `/api/admin/users/{id}/resend-invite` | `ORG_ADMIN` | -- | `200 {}` | `404` unknown or cross-org id; `400` user is not `INVITED` |
| POST | `/api/auth/set-password` | public (token is the credential) | `{token, newPassword}` | `200 {}` + session cookie | `400` bad/expired/already-used token (invite is single-use: only an `INVITED` user), weak password |
| GET | `/api/onboarding` | `ROLE_ONBOARDING` | -- | `OnboardingData` | |
| PUT | `/api/onboarding/personal` | `ROLE_ONBOARDING` | `OnboardingPersonalForm` (flat, below) | `OnboardingData` | `400` |
| PUT | `/api/onboarding/emergency` | `ROLE_ONBOARDING` | `EmergencyContactForm` `{emergencyName, emergencyRelationship, emergencyPhone}` | `OnboardingData` | `400` |
| PUT | `/api/onboarding/pay-ids` | `ROLE_ONBOARDING` | `PayIdsForm` `{bankAccount, bankIfsc, nationalId, taxId, passport}` | `OnboardingData` | `400` |
| POST | `/api/onboarding/submit` | `ROLE_ONBOARDING` | -- | `200 {}` | `400` a step is incomplete (lists which) or user is not `PENDING` |
| PATCH | `/api/hr/employees/{id}/details` | `ORG_ADMIN`, `HR` (HR only on `EMPLOYEE` rows) | `EmpDetailsForm` `{phone, personalInfo, address, emergencyContact, payIds}`, every section optional, `null` = unchanged | `200 {}` | `404` cross-org/unknown; `403` HR on a non-EMPLOYEE row; `400` |

`OnboardingPersonalForm` (PUT `/personal`): `phone, dateOfBirth (YYYY-MM-DD), gender,
nationality, maritalStatus, bloodGroup, personalEmail, address1, address2, city, landmark,
postalCode, state, country`. There is **no** `/address` endpoint -- the address is part of
the personal step.

`OnboardingData`: `name, email, status, nextStep, personalDone, emergencyDone, payIdsDone,
phone, personalInfo{dateOfBirth, gender, nationality, maritalStatus, bloodGroup,
personalEmail}, address{address1, address2, city, landmark, postalCode, state, country},
emergencyContact{emergencyName, emergencyRelationship, emergencyPhone}, bankAccount,
bankIfsc, nationalId, taxId, passport`. `personalDone` covers the details **and** the address.
`nextStep` = first incomplete of `personal` | `emergency` | `payIds`, else `review`. It is the
caller's own data, so nothing is masked (the directory masks; this does not).

Each PUT replaces its step (a blank/omitted optional field clears it) and validates it; a
rejected PUT changes nothing (transaction rolls back).

## Mandatory vs optional

| Step | Mandatory | Optional |
|---|---|---|
| personal | phone, dateOfBirth, gender, nationality, maritalStatus, personalEmail, address1, city, state, postalCode, country | bloodGroup, address2, landmark |
| emergency | emergencyName, emergencyRelationship, emergencyPhone | -- |
| payIds | bankAccount, bankIfsc, nationalId (government ID), taxId (tax ID) | passport |

Submit re-checks all three (`personal` and `address` reported separately). Invite: name,
email, role. Bulk import: name, email, role per row -- see `bulk-employee-import.md`.

## Validation (`workflow/FieldRules`, applied before the required check)

`null` stays `null` (PATCH: unchanged), blank becomes `""`, otherwise trimmed. Lengths equal the
column lengths, so a value that passes never reaches the database as an over-long value.

| Rule | Applies to |
|---|---|
| free text: max length, no control characters, no `<` or `>` | name 128, gender 50, maritalStatus 40, address1/2 150, landmark 150, city 100, state 100, emergencyName 128, emergencyRelationship 50 |
| email: `x@y.z` shape, no spaces, max 190 | email, personalEmail |
| phone: digits, `+`, spaces, brackets, hyphens; 7-15 digits; max 20 | phone, emergencyPhone |
| ISO 3166-1 alpha-2 (upper-cased) | nationality, country |
| blood group: one of A+ A- B+ B- AB+ AB- O+ O- | bloodGroup |
| postal code: 3-20 letters/digits/space/hyphen | postalCode |
| IFSC: `^[A-Z]{4}0[A-Z0-9]{6}$` (upper-cased) | bankIfsc |
| bank account: 5-34 letters/digits | bankAccount |
| ID code: 4-30 letters/digits/space/slash/hyphen (upper-cased); shape only, formats differ by country | nationalId, taxId, passport |
| date of birth: 1900-01-01 .. yesterday | dateOfBirth |

One documented gap: a `PUT /personal` with a null `dateOfBirth` after one was already saved keeps
the saved value instead of failing the required check.

## Security notes

- **`ROLE_ONBOARDING`.** `JwtAuthFilter` gives a `PENDING` user's session **only** that authority
  (status is read from the database on each request via `UserService.findIfSessionValid`, not
  from a token claim). Every controller's `@PreAuthorize` lists real roles, so everything except
  `/api/onboarding/**` (`hasRole('ONBOARDING')`) and `/api/auth/me` rejects it. Logout is public.
- **Own row only.** `OnboardingFlow` takes the user id from the token; there is no id in any
  onboarding request.
- **Single-use invite link.** `CredentialService.setInitialPassword` requires `INVITED`; a replayed
  link is `400`. A forgot-password reset on an `INVITED` user moves them to `PENDING` (same
  place the link would have put them). `verifyLogin` rejects `INVITED` outright.
- **No temp or admin-set passwords.** Passwords are only ever set by the user from the invite or
  forgot-password mail. Signup is the one place a password is typed at creation (the org creator).
- **Status cannot be skipped.** `PATCH /api/hr/employees/{id}` cannot change the status of an
  `INVITED`/`PENDING`/`ONBOARDED` user except to `EXITED`.
- **Audit.** `PATCH .../details` writes an `audit_log` row (`EMPLOYEE_DETAILS_UPDATED`, entity
  `EMPLOYEE`, `performed_by`) whose `after_json` lists the **section names** only
  (`{"sections":["phone","payIds"]}`) -- never values; bank/ID numbers do not get copied into a
  second table.
- **Logging.** `OnboardingController` never logs request bodies (they carry bank and ID numbers).
- Touches authentication and sensitive data: get a second reviewer.

## Notifications

On submit, every `HR` and `ORG_ADMIN` user in the org gets an in-app notification
(`NotificationType.ONBOARDING_COMPLETED`, related entity `USER`/`<userId>`):
"<name or email> completed onboarding" -- set the reporting manager and employment details.

## Leave balances

Nothing is created at submit. `LeaveBalanceService` accrues lazily from the joining date on first
read; creating balances at onboarding would freeze accrual against the default joining date
(today) before HR corrects it.

## Data

Onboarding answers live in `employee_sensitive` (1:1 with the user, PK `user_id`); the row is
created empty when the user is invited. Employment fields stay on `employee`. No new tables or
columns. See `schema.md`.

## Database migration notes (existing MySQL databases)

Hibernate maps `@Enumerated(STRING)` to MySQL `enum(...)` and `hbm2ddl.auto=update` never alters an
existing column:

- `user.status`: add `INVITED`, `PENDING`, `ONBOARDED` to the enum.
- `notification.type`: add `ONBOARDING_COMPLETED`.
- `user.directory_profile_complete` is `NOT NULL` with no default and no longer mapped: drop it
  (``ALTER TABLE `user` DROP COLUMN directory_profile_complete``) or every insert fails.
- `hr_approved` (already unmapped earlier) may still exist; harmless.

## Relevant files

`rest/AdminUserController`, `rest/OnboardingController`, `rest/HrEmpController` (`/details`),
`workflow/OnboardingFlow`, `workflow/OnboardingSupport` (required-field lists),
`workflow/FieldRules` (input rules), `workflow/EmpDetailsFlow`, `workflow/HrEmpFlow`
(`invite`, `resendInvite`), `service/CredentialService` (login, hashing, set/reset password),
`service/UserService` (identity + `markOnboarded`), `service/AuditLogService`,
`service/EmpSensitiveService`, `filter/JwtAuthFilter`, forms `UserInviteForm`,
`OnboardingPersonalForm`, `PayIdsForm`, `EmpDetailsForm`, data `OnboardingData`.

## Frontend changes required

1. **Gate on `status`, not `directoryProfileComplete`.** The flag is gone from `/api/auth/me`
   and the employee rows. `PENDING` -> onboarding form; `INVITED` cannot log in (show "check
   your invite email"); `ONBOARDED`/`ACTIVE` -> app.
2. **Invite set-password page** (`app.invite.link-base`): after `POST /api/auth/set-password`
   succeeds, redirect to the onboarding form (the cookie is already set).
3. **Onboarding screens**: three steps -- Personal details (incl. address), Emergency contact,
   Pay & IDs -- each `PUT`; resume at `nextStep` from `GET /api/onboarding`; a preview page from
   the same `GET` with an Edit link per step; **Submit** -> `POST /api/onboarding/submit`, then
   redirect to login. Map field errors from the `400` summary (it names the field). Do not send
   properties outside the documented bodies (unknown properties are a `400`).
4. **Bulk import**: response is `{created, skipped[]}` only -- no `credentials` / temp
   passwords to show or download. `name` is now mandatory per row alongside `email` and `role`.
   Admin only.
5. **Admin-only "Invite user" screen**: name, email, role (EMPLOYEE/HR) -> `POST
   /api/admin/users`; **Resend invite** action for `INVITED` rows. Remove the HR "Add employee"
   form (`POST /api/hr/employees` is gone) and any password / force-reset fields on the HR edit
   form (`PATCH` no longer accepts `password`).
6. **Employees list**: show a status badge and a filter (Invited / Pending / Onboarded /
   Active / On notice / Exited). Status of an Invited/Pending/Onboarded user can only be
   changed to Exited.
7. **HR/admin details edit screen**: `PATCH /api/hr/employees/{id}/details` (sections
   optional, send only what changed). Reporting manager and employment fields keep using
   `PATCH /api/hr/employees/{id}`; only Onboarded/Active users should be offered as managers.
8. **Directory**: `GET /api/directory` now returns the personal/address/emergency/pay details
   and salary to `HR` as well as `ORG_ADMIN` (sensitive numbers masked to the last 4). It is
   read only: `POST/PATCH/DELETE /api/directory` are gone -- use the endpoints above.
