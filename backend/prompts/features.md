# Features

Reference for what's in `prompts/features/`. See `prompts/README.md` for how this folder
fits into the rest of `prompts/`.

## What lives here

One flat file per feature, named after the feature:

| File | Purpose |
|---|---|
| `<feature_name>.md` | The feature's spec. For a feature not yet built, this is what to implement. For a built feature, it should reflect what's actually there -- updated after implementation, not left as the original plan. |
| `<feature_name>.changes.md` | Present only while a change to an already-built feature is being planned/implemented. Folded back into `<feature_name>.md` once done, then deleted. |

Section structure for a feature doc: Overview, Edge Cases, REST API, Workflow, Business
Logic, POJOs, Database Indexes, Database, External Libraries, Configuration Parameters,
Relevant Files -- the Spring-specific names from `spring-backend-standards`'
`references/features.md`. Omit sections that don't apply.

## Rules specific to this folder

- A feature doc here is a record of intent or of what's built -- **never a work queue**.
  Don't start implementing one because it exists; read it only once the user has named
  that feature.
- No code is written until the user has approved the doc (or the `.changes.md`), per the
  planning workflow in `/deployza-code:feature`.
- Keep one file per feature or tightly-coupled feature group -- don't let a single doc
  sprawl to cover unrelated endpoints just because they landed in the same session.

## Features built so far

| Feature | Status |
|---|---|
| [employee-auth-and-profile](features/employee-auth-and-profile.md) | Implemented |
| [forgot-password](features/forgot-password.md) | Implemented |
| [filter-package-reorg](features/filter-package-reorg.md) | Implemented |
| [employee-onboarding](features/employee-onboarding.md) | Implemented (2026-09-30) -- admin invite, set password, onboarding form, HR/admin details edit |
| [hr-employee-management](features/hr-employee-management.md) | Implemented; updated 2026-09-30 (no HR create, no passwords, status rules, `/details`) |
| [employee-directory](features/employee-directory.md) | Implemented; updated 2026-09-30 (read only, sensitive block to ORG_ADMIN and HR) |
| [bulk-employee-import](features/bulk-employee-import.md) | Implemented; updated 2026-09-30 (ORG_ADMIN only, invites instead of temp passwords) |
