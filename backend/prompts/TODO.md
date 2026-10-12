# TODO

The canonical backlog -- future work, not currently in progress. See `README.md` for the
rules on maintaining this file (intent, not instructions; picked up only when named
explicitly).

## Domain features awaiting a REST/service layer

Entities already have `*Pojo`/`*Repo` classes and Flyway migrations, but no controller
or workflow yet.

- [✅] SignUp page
- [ ] Validation is remaining


- [x] Leave types (HR admin CRUD) -- `GET/POST/PATCH/DELETE /api/hr/leave-types`, `GET /api/leave-types`
- [x] Leave requests (employee submit/view/cancel) -- `POST /api/leave-requests`, `GET /api/leave-requests/me`, `/{id}`, `/{id}/cancel`
- [x] Leave request approvals (manager + HR approve/reject) -- unified `POST /api/leave-requests/{id}/action`
- [x] Leave ledger entries (balance tracking) -- `GET /api/leave-balances/me`; naive pro-rata annual accrual on first read, DEBIT on approval. No quarterly-PL cadence, carry-forward cap, or scheduled accrual job yet (`LeaveBalanceService` ponytail note)
- [x] Public holidays (read) -- `GET /api/hr/holidays`; 2026 calendar seeded (`V26`). HR create/delete still deferred (unwired in the frontend)
- [ ] Exit settlements / PL encashment
- [ ] Audit log (query/read endpoints)
- [ ] `expectedDeliveryDate` persistence for maternity (accepted and dropped today -- no column)
- [ ] `situationType` lookup-table validation; miscarriage-lapses-maternity linkage
- [ ] `leave_type` / `public_holiday` are global reference data -- add `organization_id` when a second tenant needs its own catalog

- [ ] Notifications


Future TODO
- [ ] Captcha
- [ ] Exit Settlememt

## Identity split follow-ups (see prompts/features/multi-tenancy.md, 2026-08-31)

- [ ] `admin`/`hr` 1:1 detail table -- add only when an admin-only column actually
      exists; wire a `role` -> detail-table switch into `EmpFlow.toData`.
- [ ] `PENDING` `role` value on `user` -- lands with the HR-creates-employee
      endpoint (creates the row before the role is decided).
- [ ] `prompts/schema.md` still documents the dropped `session` and `exit_settlement`
      tables (dropped in `V18`/`V20`) -- prune or mark.

## Modules not started

Per `leave-frontend/fronted/API-REQUIREMENTS.md`.

- [ ] Appraisals
- [ ] HR admin CRUD beyond leave types/holidays (e.g. employee management)

## Architecture / docs to resolve

- [x] **Reconcile conflicting multi-tenancy/auth designs across `prompts/features/`.**
      Corrected 2026-08-26: the earlier same-day resolution (`orgCode` login field +
      cookie sessions wins) is reversed. `leave-frontend/Prompts/multi-tenancy.md` is
      the authoritative external design doc for this concern, confirmed with the user
      as binding in full: subdomain-resolved tenant, JWT bearer auth (not cookie
      sessions), `organization_id` on *every* business table, and a separate
      `platform_admin`/`SUPER_ADMIN` provisioning realm. `database-schema.md`,
      `backend-api-design.md`, and `auth-and-security.md` were right all along --
      `prompts/features/multi-tenancy.md` has been rewritten to match them and is now
      the implementation-ready planning doc.
- [ ] `backend-api-design.md`, `database-schema.md`, and `multi-tenancy.md` link to
      several feature docs that don't exist yet in `prompts/features/`:
      `workflow-state-machines.md`, `leave-policy-engine.md`, `rule-engine.md`,
      `notifications.md`, `enterprise-system-design.md` -- write them or drop the
      links. `notifications.md` blocks one concrete piece: the org-bootstrap flow's
      temp-password email currently has to stub delivery until it exists.
- [ ] **Implement multi-tenancy per `prompts/features/multi-tenancy.md`.** Large:
      new `organization`/`platform_admin` tables+entities+services, JWT issuing/
      validation (`JwtService`, new `jjwt-*` dependency), a subdomain-resolution
      filter + `TenantContext`, a Hibernate `@Filter` enabled per-request,
      `organization_id` added to all nine existing tenant-owned tables plus scoped
      unique constraints, the `/platform-api/**` bootstrap endpoint
      (org + first `HR` employee in one call), and **reworking the
      already-built `employee-auth-and-profile` feature**: cookie/`session`-table
      auth is replaced by JWT, so `SessionPojo`/`SessionRepo`/`SessionService` and
      the `session` table are removed, not just added alongside.
