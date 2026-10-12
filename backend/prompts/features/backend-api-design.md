# Backend API Design

REST + JSON. Base path `/api`. All endpoints require `Authorization: Bearer <jwt>` except `/api/auth/login`. Role/relationship checks are enforced server-side (Spring Security `@PreAuthorize` + explicit ownership checks in the service layer) — the frontend hiding a button is UX, not security.

**Multi-tenant scoping**: every `/api/**` request is implicitly scoped to the tenant resolved from the request subdomain (see [multi-tenancy.md](multi-tenancy.md)) — no endpoint below takes an `organizationId` parameter, because there's never a legitimate reason for one request to name a different tenant than the one it arrived on. Tenant provisioning itself lives on a separate, non-tenant-scoped surface: `/platform-api/**` (`SUPER_ADMIN`-only, separate auth realm) — `POST /platform-api/organizations`, `POST /platform-api/organizations/{id}/suspend`\|`/reactivate`, `GET /platform-api/organizations`.

## Conventions

- Standard REST verbs/status codes: `200`/`201` success, `400` validation error, `401` no/invalid token, `403` authenticated but not authorized, `404` not found, `409` conflict (e.g. overlapping leave dates).
- Error body shape: `{ "error": "VALIDATION_ERROR", "message": "...", "fields": {"startDate": "must be in the future"} }` — consistent across all endpoints, one `@ControllerAdvice` exception handler.
- Pagination on list endpoints: `?page=0&size=20`, response wraps `{ "content": [...], "totalElements": N, "totalPages": N }`.
- Dates as ISO-8601 (`YYYY-MM-DD`), timestamps as ISO-8601 with offset.
- DTOs, not entities, cross the controller boundary — request/response classes are separate from JPA entities so schema changes don't silently reshape the API contract.

## Auth

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{email, password}` → `{token, expiresAt}`. Fails generically ("invalid credentials") — never reveals whether the email exists. |
| POST | `/api/auth/change-password` | Requires the current password — no forced-reset-on-first-login gate (see [multi-tenancy.md](multi-tenancy.md) for the removed `force_password_reset` column). |

## Employee self-service

| Method | Path | Notes |
|---|---|---|
| GET | `/api/employees/me` | own profile |
| GET | `/api/leave-types` | active types applicable to caller's `employee_type` |
| GET | `/api/holidays?year=2026` | public + weekly holidays for the year |
| POST | `/api/leave-requests` | apply — body includes `leaveTypeId`, `startDate`, `endDate`, `reason`, optional `situationType` |
| POST | `/api/leave-requests/{id}/cancel` | only own request, only while cancellable (see [04](workflow-state-machines.md#cancellation)) |
| GET | `/api/leave-requests/me?status=&year=` | own history |
| GET | `/api/leave-balances/me` | current balance per leave type, computed from the ledger (see [02](database-schema.md#leave_ledger_entry)) |
| GET | `/api/notifications/me?unread=true` | |
| POST | `/api/notifications/{id}/read` | |

## Manager

Every endpoint here additionally checks `employee.managerId == callingUser.id` at the service layer — a manager only ever sees/acts on their direct reports, never the whole company.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/manager/team` | list of direct reports |
| GET | `/api/manager/leave-requests?status=PENDING_MANAGER` | queue awaiting this manager |
| POST | `/api/manager/leave-requests/{id}/approve` | body: `{comments}` |
| POST | `/api/manager/leave-requests/{id}/reject` | body: `{comments}` (required on reject) |

## HR / Admin

`@PreAuthorize("hasRole('HR')")` on the whole controller.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/hr/leave-requests?status=&department=&year=` | company-wide view |
| POST | `/api/hr/leave-requests/{id}/approve` \| `/reject` | HR-step actions |
| POST | `/api/hr/leave-requests/{id}/notes` | dispute/override notes, audit-logged |
| POST | `/api/hr/lop` | impose disciplinary LOP — body `{employeeId, startDate, endDate, reason}` |
| GET | `/api/hr/lop-watchlist` | employees flagged by `LopReturnCheckJob` |
| CRUD | `/api/hr/employees` | includes `managerId`, `basicSalary`, `employeeType` |
| POST | `/api/hr/employees/{id}/exit` | initiates exit, sets `dateOfExit`, `status=ON_NOTICE` |
| POST | `/api/hr/exit-settlements/{id}/finalize` | writes ENCASHMENT ledger entry, sets `status=FINALIZED` — see [03](leave-policy-engine.md#exit-encashment-calculation) |
| CRUD | `/api/hr/leave-types` | catalog config |
| CRUD | `/api/hr/leave-rules` | eligibility/approval-override rule expressions — see [rule-engine.md](rule-engine.md) |
| CRUD | `/api/hr/leave-types/{id}/approval-steps` | approval chain config |
| CRUD | `/api/hr/holidays` | public holiday calendar |
| POST | `/api/hr/ledger-adjustments` | manual correction — body `{employeeId, leaveTypeId, amountDays, note}`, always audit-logged |
| GET | `/api/hr/audit-log?entityType=&from=&to=` | |

## Validation rules enforced server-side (not just frontend)

- `startDate <= endDate`, both in the future for non-emergency leave types.
- No overlapping `leave_request` date ranges for the same employee in a non-terminal status.
- `requires_advance_notice_days` on the leave type checked against `applied_at` vs `startDate`.
- LOP application blocked unless CL and PL balances are both `<= 0` (see [03](leave-policy-engine.md)).
- `situationType` required and validated against the lookup table when `leave_type.is_situation_based = true`.

## What's deliberately not built for v1

- No GraphQL, no separate BFF layer — three roles and one SPA don't need either; plain REST is enough.
- No bulk-import endpoint for employees — HR creates them one at a time via the CRUD endpoint; add a CSV import only if onboarding volume makes that painful in practice.
