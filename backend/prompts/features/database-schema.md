# Database Schema

MySQL 8+. This doc is also the domain model — in a JPA/Hibernate app the entity classes mirror these tables directly, so there's no separate "domain model" document. Types are given generically (`VARCHAR`, `DECIMAL`, `DATE`, `DATETIME`, `ENUM`/lookup table, `JSON`) rather than exact DDL, since column widths are an implementation detail. If the entity classes and this document ever disagree, the entity classes are the implementation source of truth — update this document to match, not the other way around.

Schema changes ship as versioned Flyway migrations (see [enterprise-system-design.md](enterprise-system-design.md#cicd-pipeline)), never `hbm2ddl.auto=update` against a live database. One consequence for the `ENUM` columns below: **`ENUM` in this doc always means a `VARCHAR` column with the value set enforced by the Java enum (`@Enumerated(EnumType.STRING)`), never a native SQL `ENUM(...)` type.** A native `ENUM(...)` column locks in exactly the values present when it was created — adding one more means an `ALTER TABLE ... MODIFY COLUMN` migration that has to land in every environment *before* any code that writes the new value ships, turning a routine "add a leave-type status" change into a schema migration instead of a plain data change. `VARCHAR` sidesteps that entirely: a new enum constant is just a new string a later migration never has to touch.

**Multi-tenant note**: every table below except `organization` and `platform_admin` carries an `organization_id BIGINT NOT NULL FK → organization.id` (first column, and first column of every composite index) — see [multi-tenancy.md](multi-tenancy.md) for the resolution/enforcement mechanism. Columns are listed without repeating `organization_id` on every table to keep this doc scannable; assume it's there.

## ORM mapping convention: plain FK columns, no JPA relationships

Every "FK →" in this doc is a **DB-level foreign key constraint only**. The JPA entity classes do **not** use `@ManyToOne`, `@OneToOne`, `@OneToMany`, or `@ManyToMany` — every relationship is a plain `Long xxxId` field (e.g. `LeaveRequest.employeeId`, not `LeaveRequest.employee`). This is a deliberate constraint on the persistence layer, not an oversight:

- **No lazy-loading traps.** A plain `Long` field can't throw `LazyInitializationException` outside a transaction, because there's no lazy proxy to walk in the first place. Code that needs the related row calls the repository explicitly (`employeeRepository.findById(request.getEmployeeId())`), inside whatever transaction/service method actually needs it.
- **No accidental N+1 queries or surprise cascades.** Without relationship annotations, there's no `CascadeType.ALL` or `FetchType.EAGER` default anyone can get wrong — every read and every write is an explicit, visible repository call. Deleting an entity never implicitly deletes things it's related to; a service method that wants to delete related rows does so explicitly, one repository call per table, so the blast radius of any write is exactly what's written in the code.
- **Cheaper joins when they're actually needed.** A multi-table read (e.g. "leave request with employee name") is a single explicit JPQL/native query with a `JOIN`, written where it's needed — not an implicit graph walk that may or may not be efficient depending on fetch-type tuning.

This trades a small amount of convenience (no `request.getEmployee().getName()` navigation) for predictability — every DB access in the codebase is a repository call you can grep for, not an object-graph traversal whose actual SQL depends on Hibernate's fetch strategy for that field.

## Constraint policy: minimal, and only where it protects real data integrity

DB-level `FOREIGN KEY` constraints are added only on relationships where an orphaned/dangling reference would be a real, otherwise-easy-to-introduce data bug — not as a blanket "constrain everything" default:

- **Kept**: `employee.manager_id`, `leave_request.employee_id`/`leave_type_id`, `leave_request.parent_request_id`, `leave_ledger_entry`'s references, and the rest listed per-table below — these protect against a row pointing at nothing, which is the actual failure mode worth a DB-level guarantee.
- **No `ON DELETE CASCADE` anywhere.** A cascading delete silently destroying rows in an append-only ledger or audit table (`leave_ledger_entry`, `audit_log`) is exactly the kind of constraint that "helps" until it quietly erases history. Every FK uses the default `RESTRICT`/`NO ACTION` behavior — if something needs to go away, a service method deletes it explicitly, table by table, so what's being removed is always visible in the code doing the removing.
- **No compound/multi-column FK constraints.** Tenant scoping (`organization_id`) is enforced at the application/Hibernate-filter level (see [multi-tenancy.md](multi-tenancy.md)), not by folding `organization_id` into every FK as a compound key — that would multiply the constraint surface for a guarantee the filter layer already provides.
- **No FK constraint where the relationship is genuinely optional/informational only** — e.g. `leave_ledger_entry.created_by` uses `RESTRICT` like everything else, but low-value, high-churn logging fields aren't given extra constraints (uniqueness, additional check constraints) beyond what the column already needs to do its job.

## `organization`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `name` | VARCHAR | |
| `subdomain` | VARCHAR UNIQUE | e.g. `acme` |
| `hr_notify_email` | VARCHAR | per-tenant HR cc address, see [07](notifications.md) |
| `status` | ENUM(`ACTIVE`,`SUSPENDED`) | |
| `created_at` | DATETIME | |

## `platform_admin`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `name` | VARCHAR | |
| `email` | VARCHAR UNIQUE | globally unique — this table is never `organization_id`-scoped, so there's no per-tenant composite to fall back on |
| `password_hash` | VARCHAR | BCrypt |
| `created_at` | DATETIME | |

Deliberately **not** a row in `employee` with a special role value — a platform admin isn't an employee of any tenant company, and this table backs a genuinely separate auth realm (`/platform-api/**`: separate login, separate session/token, separate filter chain) from the tenant-scoped `/api/**` surface. See [multi-tenancy.md](multi-tenancy.md#decisions) (item 6, provisioning) and its `## REST API` section for what it's used for. No `role` column: every row is implicitly `SUPER_ADMIN`; add one only if a second platform-admin tier is ever actually needed.

## `employee`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `employee_code` | VARCHAR UNIQUE per org | human-readable ID (e.g. `DPZ-0142`) |
| `name` | VARCHAR | |
| `email` | VARCHAR, UNIQUE `(organization_id, email)` | login identity — unique within a company, not globally (two tenants can each have a `jane@example.com`) |
| `phone` | VARCHAR | |
| `password_hash` | VARCHAR | BCrypt; a `NULL` value means the account has no usable password yet and must go through set-password before login |
| `date_of_joining` | DATE | drives pro-rata annual credit |
| `date_of_exit` | DATE NULL | set when exit is initiated |
| `employee_type` | ENUM(`REGULAR`,`INTERN`) | intern accrual rules differ entirely |
| `designation` | VARCHAR | |
| `department` | VARCHAR | |
| `manager_id` | BIGINT NULL, FK → `employee.id` | self-referencing; NULL = top of hierarchy |
| `basic_salary` | DECIMAL(12,2) NULL | needed for exit encashment math |
| `role` | ENUM(`EMPLOYEE`,`HR`) | only two stored roles — see [06](auth-and-security.md) |
| `status` | ENUM(`ACTIVE`,`ON_NOTICE`,`EXITED`) | |
| `created_at`, `updated_at` | DATETIME | |

Index: `manager_id` (every "get my team" query filters on it), `email` (login lookup), `status`.

## `leave_type`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `code` | VARCHAR UNIQUE | `CL`, `PL`, `MARRIAGE`, `MATERNITY`, `PATERNITY`, `SABBATICAL`, `LOP`, `INTERN_STUDY`, `PUBLIC_HOLIDAY`, `OPTIONAL_HOLIDAY` |
| `name` | VARCHAR | display name |
| `applicable_employee_type` | ENUM(`ALL`,`REGULAR`,`INTERN`) | interns don't see CL/PL/Marriage etc. |
| `accrual_strategy` | ENUM(`ANNUAL_PRORATA`,`PERIODIC`,`EVENT_BASED`,`NONE`) | see [03](leave-policy-engine.md) |
| `accrual_config` | JSON | e.g. `{"cadence":"QUARTERLY","daysPerPeriod":4.75,"annualDays":19}` — shape varies by strategy |
| `max_carry_forward_days` | DECIMAL NULL | NULL = no carry-forward |
| `requires_advance_notice_days` | INT NULL | e.g. 30 for Marriage/Maternity/Paternity |
| `is_encashable` | BOOLEAN | true for PL at exit |
| `is_situation_based` | BOOLEAN | true only for Maternity (duration depends on `situation_type`) |
| `is_active` | BOOLEAN | soft-disable instead of deleting a type with history |

This table is the single seed point for policy numbers. Every contradiction in [open-questions.md](open-questions.md) resolves to values here, once HR confirms them — no code changes either way.

## `leave_type_approval_step`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `leave_type_id` | BIGINT FK → `leave_type.id` | |
| `step_order` | INT | 1, 2, ... |
| `approver_role` | ENUM(`MANAGER`,`HR`) | |
| `is_mandatory` | BOOLEAN | reserved for future optional-step cases; every step is mandatory today |

Unique constraint on (`leave_type_id`, `step_order`). This table *is* the configurable approval chain — see [workflow-state-machines.md](workflow-state-machines.md) for how the engine walks it.

## `leave_request`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `employee_id` | BIGINT FK → `employee.id` | |
| `leave_type_id` | BIGINT FK → `leave_type.id` | |
| `start_date`, `end_date` | DATE | |
| `num_days` | DECIMAL(5,1) | half-day aware |
| `reason` | VARCHAR/TEXT | |
| `situation_type` | VARCHAR NULL | maternity sub-case: `UP_TO_2_CHILDREN`, `2_OR_MORE_CHILDREN`, `ADOPTION_UNDER_3MO`, `COMMISSIONING_MOTHER`, `MISCARRIAGE`, `PREGNANCY_ILLNESS_EXTENSION` |
| `status` | ENUM | see state machine in [04](workflow-state-machines.md) |
| `current_step_order` | INT | which approval step is pending |
| `is_hr_imposed` | BOOLEAN | true for disciplinary LOP created directly by HR |
| `hr_notes` | TEXT NULL | dispute/override notes |
| `parent_request_id` | BIGINT NULL, FK → `leave_request.id` | links a miscarriage-triggered 6-week request back to the original maternity request it cut short |
| `applied_at`, `cancelled_at` | DATETIME NULL | |

Index: (`employee_id`, `status`), (`leave_type_id`, `status`) for HR/manager queue queries.

## `leave_request_approval`

One row per approval step *instance* for a request — the audit trail of who acted at each step.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `leave_request_id` | BIGINT FK | |
| `step_order` | INT | mirrors `leave_type_approval_step.step_order` at time of application |
| `approver_role` | ENUM(`MANAGER`,`HR`) | |
| `approver_employee_id` | BIGINT FK → `employee.id` NULL | filled in when acted on |
| `action` | ENUM(`PENDING`,`APPROVED`,`REJECTED`) | |
| `comments` | TEXT NULL | |
| `acted_at` | DATETIME NULL | |

## `leave_ledger_entry`

Append-only. **No mutable balance column anywhere in the schema** — see rationale in [architecture-overview.md](architecture-overview.md#key-architectural-decisions-why-not-just-what).

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `employee_id` | BIGINT FK | |
| `leave_type_id` | BIGINT FK | |
| `leave_year` | INT | calendar year this entry belongs to |
| `entry_type` | ENUM(`ACCRUAL`,`DEBIT`,`CARRY_FORWARD`,`ENCASHMENT`,`ADJUSTMENT`,`EXPIRY`) | |
| `amount_days` | DECIMAL(5,1) | signed: positive for credit, negative for debit |
| `effective_date` | DATE | |
| `reference_leave_request_id` | BIGINT NULL, FK → `leave_request.id` | set for DEBIT entries |
| `created_by` | BIGINT FK → `employee.id` | system jobs use a reserved system-user id, or a nullable `created_by_job` VARCHAR — pick one, keep it consistent |
| `created_at` | DATETIME | |

Current balance for (employee, leave_type, year) = `SUM(amount_days) WHERE employee_id=? AND leave_type_id=? AND leave_year=?`. Index on (`employee_id`, `leave_type_id`, `leave_year`) makes this cheap. Add a cached `leave_balance_snapshot` table only if this SUM is a measured bottleneck — skip it for v1.

## `public_holiday`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `holiday_date` | DATE UNIQUE | |
| `name` | VARCHAR | |
| `year` | INT | denormalized for fast year-range queries |

Weekly holidays (every Sunday) are **not** a table — they're a `DayOfWeek.SUNDAY` check in date-math helper code. Optional/floating holidays are **not** a calendar table either — they're the `OPTIONAL_HOLIDAY` leave type in `leave_type`, applied like any other leave against any date the employee picks.

## `notification`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `employee_id` | BIGINT FK | recipient |
| `type` | VARCHAR | e.g. `LEAVE_APPLIED`, `LEAVE_APPROVED`, `LOP_IMPOSED` — matches the trigger matrix in [07](notifications.md) |
| `title`, `message` | VARCHAR/TEXT | |
| `related_entity_type`, `related_entity_id` | VARCHAR, BIGINT NULL | e.g. `LEAVE_REQUEST`, 42 |
| `is_read` | BOOLEAN | |
| `created_at` | DATETIME | |

## `audit_log`

Hand-written from the service layer at sensitive mutation points (HR override, imposed LOP, leave-type config changes) — not a generic entity-listener/Envers setup, since only a handful of call sites need it.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `entity_type`, `entity_id` | VARCHAR, BIGINT | what was changed |
| `action` | VARCHAR | e.g. `LOP_IMPOSED`, `LEAVE_TYPE_UPDATED` |
| `performed_by` | BIGINT FK → `employee.id` | |
| `before_json`, `after_json` | JSON NULL | snapshot of the change |
| `created_at` | DATETIME | |

## Entity relationship summary

```
organization ──1:N──▶ every table below (organization_id on each)

platform_admin ──(no FK to anything)──▶ standalone, separate auth realm —
                  creates organization rows via /platform-api but has no
                  column referencing one

employee ──(manager_id, self-FK)──▶ employee
employee ──1:N──▶ leave_request
employee ──1:N──▶ leave_ledger_entry
employee ──1:N──▶ notification

leave_type ──1:N──▶ leave_type_approval_step
leave_type ──1:N──▶ leave_request
leave_type ──1:N──▶ leave_ledger_entry

leave_request ──1:N──▶ leave_request_approval
leave_request ──1:N──▶ leave_ledger_entry (DEBIT entries)
leave_request ──0..1:N──▶ leave_request (parent_request_id, e.g. miscarriage → new request)
```
