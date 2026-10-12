# Forgot Password (self-service reset)

## Status: Implemented (2026-09-03)

Built as planned below. Full test suite green (202 tests): `JwtServiceTest` +5
(`pwd-reset` round-trip, rejects session / email-verify / garbage tokens, and
`parseEmailVerify` rejects a `pwd-reset` token), `AuthFlowTest` +8 (forgot→reset happy
path with old password rejected, unknown org/email silent no-op, blank input 400,
cooldown silent no-op, garbage token 400, session token 400, weak password 400, lockout
cleared on reset).

**One deliberate simplification vs the plan:** `PasswordResetMailer` sends a
name-personalised **subject** but *not* the "Account details" (employee code /
designation / department) block that `VerificationMailer` carries. That block makes sense
in a "welcome, here's your account" mail; in a password-reset mail it is noise and a
minor info-leak risk if the message is misdelivered. Subject personalisation via
`EmpService.findByUserIdOrNull` is kept.

**This is an authentication change** — flag it for a second reviewer at
`/deployza-code:review` (nothing else gates it).

No self-service reset exists today. Passwords are set at signup (org admin, self-chosen)
or by an HR/ORG admin (`PATCH /api/hr/employees/{id}` with `password`). The frontend
`forgot-password.html` currently just tells the user to contact HR
(`leave-frontend/fronted/prompts/features/forgot-password.md`).

This feature adds the two-step emailed-link flow the frontend doc already anticipates:
request a link, then set a new password from the link.

## Overview

Two public endpoints on the existing `AuthController`:

1. `POST /api/auth/forgot-password` — caller gives `{orgKey, email}`. If that
   resolves to a real, usable account we email a reset link; either way the response is
   an identical `200 {}` (no account enumeration).
2. `POST /api/auth/reset-password` — caller gives `{token, newPassword}` from the link.
   Valid token → password is replaced and the account is unlocked. Response `200 {}`.
   **No session cookie is issued** — the user then logs in normally.

The link in the email points straight at a **new frontend page**
(`reset-password.html?token=...`), which collects the new password and calls
`POST /api/auth/reset-password`. Unlike `verify-email` (which hits the backend first and
302s), there is no server-side pre-check hop — the token is validated when
`reset-password` is called, and CORS already allows the frontend origin.

### Token mechanism (decided)

A **stateless, purpose-scoped JWT**, exactly like the existing email-verification token
(`JwtService.issueEmailVerify` / `parseEmailVerify`). New purpose value `pwd-reset`,
**30-minute TTL** (hardcoded constant `PWD_RESET_TTL_MS`, mirroring
`EMAIL_VERIFY_TTL_MS`). No database column, no migration, no server-side token store.

**Deliberate limitation:** the token is not single-use and cannot be revoked before it
expires — a reset link keeps working for its full 30 minutes even after the password has
been changed. Accepted because it matches what is already built for `verify-email`, and
the 30-minute window (vs `verify-email`'s 24h) bounds the exposure. Upgrade path if this
is ever not good enough: reject the token when its `iat` predates `user.updated_at`, or
move to a hashed single-use token column on `user`. Marked with a `ponytail:` comment in
`JwtService`.

> **This is an authentication change.** Per the security baseline's Scope note, it needs
> a second pair of eyes before merge — flag it in `/deployza-code:review`.

## Edge Cases

- **Account enumeration.** `POST /api/auth/forgot-password` returns `200 {}` for every
  input: unknown org key, unknown email, suspended/inactive org, `EXITED` user,
  cooldown hit, and SMTP failure all produce the same empty body. The controller never
  signals which. `reset-password` failures are generic too: a malformed token, an
  expired token, and a wrong-purpose token all return the same `400` ("invalid or
  expired reset link").
- **Authorization.** Both routes are public (`permitAll`). `forgot-password` needs no
  identity; `reset-password`'s token is the sole authority. Everything else on
  `/api/auth/**` stays behind `.anyRequest().authenticated()`.
- **Unusable accounts.** `forgot-password` does **not** send mail when the org is
  missing / `SUSPENDED` / `!active`, or when the user's `status == EXITED` — those
  accounts cannot log in, so a reset link would be pointless. Silent, same `{}`.
- **Org key matching.** `forgotPassword` trims but does not case-fold `orgKey`,
  exactly like `AuthFlow.login`. Note that `organization.org_key` is a
  `utf8mb4_0900_ai_ci` column, so the lookup is case- and accent-insensitive at the DB
  regardless — `MANISH` resolves the `manish` org. That is app-wide behaviour (`login`
  included), not specific to this flow. `AuthController` logs the submitted `orgKey`
  at INFO (same as `login`/`signup`) so a "why did this match" question is answerable
  from the log.
- **`NULL password_hash`** (an invited user who never set a password — not reachable
  today, `createUser` always writes a hash, but multi-tenancy.md plans for it):
  `reset-password` simply writes the first hash, which doubles as activation. Acceptable;
  no special-casing.
- **Mail bombing / abuse.** Anyone can POST `forgot-password` for a known address. Mirror
  the `resend-verification` throttle: a per-`(orgKey, email)` in-memory cooldown
  (`app.reset.request-cooldown-seconds`, default 60), stamped on request not on delivery.
  A second request inside the window is a silent `200 {}` (no mail, no error — staying
  enumeration-safe). The map is pruned of entries older than the cooldown on each write
  so it stays bounded by request rate over one window; keyed by
  `orgKey.toLowerCase() + ":" + email.toLowerCase()`. `ponytail:` comment points at
  the same upgrade path as `AuthFlow.lastResendAt` (a `user` column / shared cache) if
  the app ever runs multi-instance.
- **Idempotency.** `forgot-password` mints a fresh token per call; earlier tokens stay
  valid until their own TTL. `reset-password` replayed with the same token + password
  inside the TTL just re-writes the same hash — harmless.
- **Concurrency.** Two concurrent resets → last write wins, both leave a valid hash. No
  locking needed.
- **Password transport.** `newPassword` is a `byte[]` via the existing `PwdDeserializer`,
  zeroed in a `finally` — same as `LoginForm`/`ChangePwdForm`.
- **Lockout is cleared on reset.** `UserService.resetPassword` sets `failedAttempts = 0`
  and `lockedUntil = null`: a completed reset proves inbox control, and the lockout's
  only job (slowing brute force against the *old* password) is moot once the password
  changes.
- **No auto-login.** `reset-password` returns `{}` and does not set the session cookie —
  the frontend routes the user to `login.html`. Re-authenticating after "I forgot my
  password" is reasonable and keeps session issuance out of an unauthenticated endpoint.

## API / Interface

Both under `/api/auth`, added to the existing `AuthController`.

| Method | Path | Body | Response | Auth |
|---|---|---|---|---|
| POST | `/api/auth/forgot-password` | `ForgotPwdForm {orgKey, email}` | `200 {}` — always | public |
| POST | `/api/auth/reset-password` | `ResetPwdForm {token, newPassword}` | `200 {}` on success; `400 {summary}` on bad/expired token or password-policy failure | public (token is the authority) |

**Deviation from the frontend doc.** `leave-frontend/.../forgot-password.md` and the
current `forgot-password.html` assume `{email}` alone. The multi-tenant identity key is
`(organization_id, email)` — the same reason `login`/`signup` take `orgKey`. So this
endpoint requires `orgKey` too. The frontend page and its feature doc must be updated
when that slice is built; noting it here so it isn't lost.

## Validation & Access Control

- `SecurityConfig`: add `POST /api/auth/forgot-password` and `POST /api/auth/reset-password`
  to the `permitAll` list, alongside `login` / `signup` / `logout` / `verify-email`.
- `AuthController.forgotPassword`: reject blank `orgKey` or `email` with `400`
  (`BadRequestException`) — a malformed request is not the same as a well-formed request
  for a non-existent account, and rejecting it leaks nothing. Trim both, lower-case
  `email` before lookup.
- `AuthController.resetPassword`: reject a blank `token` or empty `newPassword` with
  `400`. Password-content rules (length 8–72, not all-one-character, must not contain the
  email local-part) are enforced by the existing `UserService` validator, reused
  unchanged.
- Request-entry logging on both handlers (`log.info("POST /api/auth/forgot-password ...")`),
  **never** logging the token, the email, or the password.

## Business Logic

All orchestration in `AuthFlow` (multi-service: org + user + jwt + mailer — not a
trivial single-domain case, so not a direct `Controller → Service` call).

- **`AuthFlow.forgotPassword(String orgKey, String email)`**
  1. Prune expired cooldown entries; if `(orgKey, email)` is still in cooldown,
     return (silent).
  2. `orgSvc.findByOrgKeyOrNull(orgKey)` → if `null` / `SUSPENDED` / `!active`,
     stamp cooldown and return (silent).
  3. `userSvc.findByOrgAndEmailOrNull(orgId, email)` → if `null` or `status == EXITED`,
     stamp cooldown and return (silent).
  4. Stamp cooldown, then `pwdResetMailer.send(user.getId(), user.getEmail())`.
  - Returns `void`. The controller always responds `{}`.

- **`AuthFlow.resetPassword(String token, byte[] newPwd)`**
  1. `Long userId = jwtSvc.parsePasswordReset(token)` → `null` ⇒
     `BadRequestException("invalid or expired reset link")` (`newPwd` still zeroed).
  2. `userSvc.resetPassword(userId, newPwd)` — validates, hashes, stores, clears lockout.
  - Returns `void`.

- **`JwtService.issuePasswordReset(Long userId)`** — signed token, `purpose = pwd-reset`,
  subject = user id, `PWD_RESET_TTL_MS` (30 min). Same shape as `issueEmailVerify`.
- **`JwtService.parsePasswordReset(String token)`** — user id from a valid `pwd-reset`
  token, else `null` (bad signature, expired, or `purpose` is not `pwd-reset`). A session
  token or an `email-verify` token returns `null`.
- **`UserService.findByOrgAndEmailOrNull(Long orgId, String email)`** — wraps the
  existing `userRepo.findByOrganizationIdAndEmail(...).orElse(null)`. Non-throwing
  sibling of the lookup inside `verifyLogin`.
- **`UserService.resetPassword(Long userId, byte[] newPwd)`** — `@Transactional`. Load
  user, `validatePwd(newPwd, user.getEmail())`, `setPasswordHash(hash(newPwd))`,
  `setFailedAttempts(0)`, `setLockedUntil(null)`. `newPwd` zeroed in a `finally`.
  Structurally identical to the existing `setPassword` plus the lockout clear — kept
  separate so the admin-reset path (`setPassword`) is untouched by this feature.

- **`PasswordResetMailer`** (new `@Service`, mirrors `VerificationMailer`): builds the
  link `app.reset.link-base + "?token=" + jwtSvc.issuePasswordReset(userId)`, pulls the
  optional name from `employeeSvc.findByUserIdOrNull(userId)` for a personalised subject,
  sends via `mailSvc.send(...)` as multipart plain+HTML. **Send failure is caught and
  logged, never propagated** — a bad SMTP hop must not turn into a `500` that also tells
  the caller the address exists. Copy of `VerificationMailer`'s HTML/plain builders with
  "Reset your password" / "This link expires in 30 minutes" / "If you didn't ask to reset
  your password, ignore this email" copy. (The "Account details" block is intentionally
  omitted — see Status.)

## Data Models

New `*Form` classes in `com.leaveapp.model.form`, `@Getter @Setter`:

- **`ForgotPwdForm`** — `String orgKey; String email;`
- **`ResetPwdForm`** — `String token; @JsonDeserialize(using = PwdDeserializer.class)
  byte[] newPassword = new byte[0];`

No `*Data` classes — both endpoints return `Map.of()`.

## Data Models — Persistence

**None.** No new entity, no new column, no index, no constraint, no Flyway migration.
The reset token is a stateless JWT; the only mutated row is `user` (password hash +
lockout fields), through fields that already exist. Schema-design step: **not
applicable**.

## Background Processing

None.

## External Libraries

None. `jjwt` (token), Spring's `JavaMailSender` + `angus-mail` (SMTP via the existing
`MailService`), and `BCryptPasswordEncoder` are all already on the classpath.
`internal-libraries` checked and not applicable — this project is standalone (no
`deployza-lib-*`, per `CLAUDE.md`).

## Configuration Parameters

Add to `src/main/resources/app.properties` **and** `src/test/resources/test.properties`:

| Property | Default | Purpose |
|---|---|---|
| `app.reset.link-base` | `http://localhost:8080/reset-password.html` | Base of the emailed reset link; `?token=<jwt>` is appended. Points at the **frontend** page directly (contrast `app.verify.link-base`, which points at the backend). Override per deployment (env `RESET_LINK_BASE`). |
| `app.reset.request-cooldown-seconds` | `60` | Minimum gap between two `POST /api/auth/forgot-password` for the same `(orgKey, email)`. |

Reset-token TTL (30 min) is a hardcoded constant in `JwtService`, matching how
`EMAIL_VERIFY_TTL_MS` is done — not a per-environment knob.

## Relevant Files

- `rest/AuthController.java` — **modify**: add `forgotPassword` and `resetPassword`
  handlers (both return `Map.of()`); inject nothing new (`authFlow` already present).
- `workflow/AuthFlow.java` — **modify**: add `forgotPassword(orgKey, email)` and
  `resetPassword(token, newPwd)`; add the per-`(org,email)` cooldown map + prune helper;
  inject `PasswordResetMailer`.
- `service/JwtService.java` — **modify**: `issuePasswordReset`, `parsePasswordReset`,
  `PURPOSE_PWD_RESET` + `PWD_RESET_TTL_MS` constants; `ponytail:` note on non-single-use.
- `service/UserService.java` — **modify**: `findByOrgAndEmailOrNull(orgId, email)` and
  `resetPassword(userId, newPwd)`.
- `service/PasswordResetMailer.java` — **new**: builds and sends the reset email,
  swallows send failure. Mirrors `VerificationMailer`.
- `model/form/ForgotPwdForm.java` — **new**.
- `model/form/ResetPwdForm.java` — **new**.
- `config/SecurityConfig.java` — **modify**: `permitAll` the two new POST routes.
- `src/main/resources/app.properties` — **modify**: add the two `app.reset.*` properties.
- `src/test/resources/test.properties` — **modify**: add the same two.
- `prompts/api-endpoints.md` — **modify**: add the two endpoints as **Built** under
  "Auth & Session"; note they supersede the "Planned, unconfirmed `set-password`" row for
  the *forgot* case (the HR invite/set-password link is still separate, unbuilt work).
- `src/test/java/com/leaveapp/service/JwtServiceTest.java` — **modify**: `pwd-reset`
  round-trip; rejects a session token; rejects an `email-verify` token; `null` on
  garbage.
- `src/test/java/com/leaveapp/workflow/AuthFlowTest.java` — **modify**: happy path
  (signup → forgotPassword → grab token via `jwtSvc.issuePasswordReset` → resetPassword →
  login with the new password succeeds, old password fails); unknown email is a silent
  no-op; bad token → `BadRequestException`; password-policy failure → `BadRequestException`;
  second `forgotPassword` inside the cooldown is a silent no-op.
- `prompts/features/forgot-password.md` — **new** (this document).

## Testing Notes

- No real SMTP in tests (`test.properties` points at a refused port);
  `PasswordResetMailer` swallows the failure, so flow tests exercise everything up to and
  including token issuance without asserting on delivery — same approach as the existing
  `verifyEmail` test.
- `test.properties` currently has no `app.verify.redirect-base`; add both `app.reset.*`
  keys so the context boots.
