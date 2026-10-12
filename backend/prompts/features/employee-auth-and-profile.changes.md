# Employee Auth & Profile — Changes

## Status: Implemented

Built as planned below. `EmpServiceTest` covers the sniff/size/type validation and
clear path.

## Overview

Adds a profile photo to the existing self-service employee profile
(`employee-auth-and-profile.md`). Closes the "Profile photo" gap noted in
`prompts/features/employee-directory.md` (display only, no biometric processing —
that door stays closed per that doc's Biometric section).

Scope, as confirmed with the user:
- **Self-service only.** No HR/admin upload path in this change.
- **Storage: MySQL BLOB** on the `employee` row. This project has no Artifact
  Registry / cloud storage lib access (`CLAUDE.md`), so a `MEDIUMBLOB` column is the
  dependency-free choice — no filesystem volume to provision or lose on redeploy.
- **Limit: 2 MB, JPEG/PNG/WebP.**

## API / Interface

All under the existing `/api/employees` mapping, self only (`requireUserId()`):

- `PUT /api/employees/me/photo` — multipart/form-data, single part named `photo`.
  Replaces any existing photo. Returns `SessionData` (now carrying `hasPhoto`).
- `GET /api/employees/me/photo` — streams the raw bytes with the stored
  `Content-Type`; `404` if no photo set.
- `DELETE /api/employees/me/photo` — clears the photo (idempotent: 200 whether or
  not one was set). Returns `SessionData`.

`SessionData` (`GET`/`PATCH /me` response) gains one field: `hasPhoto` (boolean) —
the binary itself is never embedded in JSON; the frontend renders
`<img src="/api/employees/me/photo">` when true.

## Validation & Access Control

- Same auth as every other `/me` route: `requireUserId()`, 401 via
  `NotAuthenticatedException` if absent. Object-level check is implicit — a user can
  only ever touch their own row (`userId` from the session, never from the request).
- **Size**: rejected over `app.photo.max-bytes` (2 MB default) — enforced twice:
  the servlet's `MultipartConfigElement` (`AppInitializer`) as the hard ceiling, and
  an explicit check in `EmpService` for a clean `400` instead of a container
  error. `MaxUploadSizeExceededException` gets a `GlobalExceptionHandler` handler
  mapped to `400` so it doesn't fall through to the generic `500`.
- **Type**: content-type allowlist (`image/jpeg`, `image/png`, `image/webp`) plus a
  magic-byte sniff of the first few bytes against each format's known signature —
  the declared multipart content-type is client-supplied and not trusted alone.
  - `ponytail:` magic-byte check confirms the format signature, not full codec
    validity (e.g. a truncated-but-signature-valid file still passes) — full decode
    validation would need `ImageIO` for JPEG/PNG but the JDK has no built-in WebP
    reader, so a single consistent check is used across all three formats instead of
    a decode check for two and a byte-sniff for the third. Upgrade to a real decode
    (all formats) only if a malformed-but-signed file actually causes a problem
    downstream — today the bytes are just stored and streamed back, never processed.
- Empty upload (0 bytes) rejected as `400`.

## Data Models

`EmpPojo` (`employee` table), two new nullable columns, Hibernate
`hbm2ddl.auto=update` adds them on next boot — no migration file (matches this
project's no-Flyway override):

- `photo` — `MEDIUMBLOB`, nullable (`byte[]`, `@Lob`).
- `photo_content_type` — `VARCHAR(20)`, nullable (one of the three allowed MIME
  types; drives the `GET` response's `Content-Type` header).

No new index — never queried by content, only ever fetched by the existing PK
(`user_id`).

## Configuration Parameters

- `app.photo.max-bytes=${PHOTO_MAX_BYTES:2097152}` (2 MB) — same
  `app.properties` convention as `app.import.max-rows`.

## Relevant Files

- `pojo/EmpPojo.java` — add `photo` / `photoContentType` columns.
- `model/data/SessionData.java` — add `hasPhoto`.
- `service/EmpService.java` — `setPhoto` (validate + store) / `clearPhoto`.
- `workflow/EmpFlow.java` — wire the two new service calls, set `hasPhoto` in
  `toData`.
- `rest/EmpController.java` — the three new endpoints.
- `config/WebConfig.java` — register `StandardServletMultipartResolver` bean.
- `config/AppInitializer.java` — override `customizeRegistration` to set the
  servlet's `MultipartConfigElement` (Servlet 3.0 native multipart, no Apache
  Commons FileUpload dependency).
- `config/GlobalExceptionHandler.java` — handle `MaxUploadSizeExceededException`.
- `app.properties` — `app.photo.max-bytes`.
