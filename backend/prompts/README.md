# prompts/

Project-specific documentation for leave-backend: what's built, what it's built against,
and what's still to do. This is not the org's engineering standards -- those live in the
`deployza-code` plugin (`coding-standards`, `spring-backend-standards`,
`security-standards`, `internal-libraries`) and are not repeated here. This folder is
project state; the plugin is the ruleset that state is checked against.

## What's here

| File / folder | Purpose | Updated when |
|---|---|---|
| `features/` | One spec per feature, plus a folder-level index in `features.md` | A feature is planned (`/deployza-code:feature`) or implemented |
| `features.md` | Index of `features/` -- what it's for, the file-naming convention, and the list of features built so far | A feature is added, renamed, or its status changes |
| `schema.md` | Every DB table: columns, nullability, indexes, and any deviation from `leave-frontend/Prompts/02-database-schema.md` (that doc is a stale early design pass in places -- flag divergences here rather than silently matching or silently ignoring it) | A `*Pojo` entity is added or its mapping changes |
| `libraries.md` | Third-party dependencies and why each was added. Internal (`deployza-lib-*`) libraries get a name-and-version line here at most -- their API detail lives in `internal-libraries`, not here | A dependency is added to `pom.xml` |
| `TODO.md` | The one canonical backlog -- future work, not currently in progress | Someone identifies future work worth tracking |

## Rules for maintaining this folder

- **Docs before code.** A new feature, an architecture change, a new endpoint, or a
  schema change gets its doc updated *before* the source file changes, not after. This
  is the org's stated workflow (`coding-standards` → "Documentation before code"), and
  it's why `schema.md` and `libraries.md` above are written as living references, not
  changelogs.
- **`TODO.md` is intent, never instructions.** An item sitting in the backlog is not
  something to build because it's there -- it gets picked up only when the user names it
  explicitly. Don't reorganize or reprioritize existing items without being asked; just
  append new ones and remove ones that are fully shipped.
- **Feature docs are the same way.** `prompts/features/*.md` records what a feature is or
  was -- reading one is not a signal to implement or modify it. Only act on a feature
  when the user names it.
- **`schema.md` and `libraries.md` are living documents, not per-feature logs.** Don't
  create a second copy of the `employee` table's definition inside a new feature doc that
  touches it -- update the one entry in `schema.md`. Same for a dependency already listed
  in `libraries.md`.
- **When a document and the code disagree, the code wins** -- update the doc to match,
  the same convention `API-REQUIREMENTS.md` (in `leave-frontend`) already states for
  itself. A stale doc is a bug in the doc.
- **This file itself**: update the table above when a new top-level file or folder is
  added to `prompts/`, so this stays a true index rather than drifting out of date.
