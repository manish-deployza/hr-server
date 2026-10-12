# Feature: Billing / Subscription

## Status: Implemented (2026-09-28)

## Implementation Notes (post-implementation)

- **`POST /api/billing/cancel` added 2026-09-28.** No new orchestration: cancelling *is*
  switching to `FREE`, which `SubscriptionService.subscribe` already fully handles
  (cancels any existing Razorpay subscription, downgrades the local row to
  `FREE`/`ACTIVE`, clears Razorpay fields/`graceUntil`). `BillingFlow.cancel(orgId)` is a
  one-line delegate to `subSvc.subscribe(orgId, "FREE")`; no new `SubscriptionService`
  method -- the underlying transition is already covered by the existing
  `subscribe(..., "FREE")` cases in `SubscriptionServiceTest`. `BillingControllerTest`
  added (this controller had no test file before) covering just `/cancel`: the
  `ORG_ADMIN` gate and orgId-from-JWT-principal passthrough, same shape as
  `OrgControllerTest`.
- **`BillingWebhookController` added 2026-09-28** (was initially blocked -- a write got
  denied mid-build in the background implementation run; added afterward in a
  foreground session). Reads the raw request body off `HttpServletRequest` (not
  `@RequestBody byte[]` -- `WebConfig`'s one registered `HttpMessageConverter` has no
  `byte[]` support), verifies `X-Razorpay-Signature` via
  `RazorpayGatewayService.verifySignature` before any parsing, then parses just `event`
  and `payload.subscription.entity.id` with a local Jackson `ObjectMapper`. Event id is
  `X-Razorpay-Event-Id` header, else a body `event_id` field, else a SHA-256 hash of the
  raw body -- unverified against a real Razorpay payload (flagged, not assumed correct).
  A processing exception from `SubscriptionService.applyWebhookEvent` is caught and
  logged, not rethrown -- `SubscriptionScheduler.reconcile` is the actual catch-all for a
  transition that failed to apply, not Razorpay's own webhook retry, so the endpoint
  always acks `200` after a valid signature to avoid a retry storm on a permanent local
  bug.
- **`RazorpayGatewayService`'s `razorpay.enabled=true` branch is a stub**
  (`throw new UnsupportedOperationException`), not wired to the real
  `com.razorpay:razorpay-java` SDK. Its exact API surface (client construction,
  subscription create/cancel/fetch shapes, webhook signature verification) could not be
  verified against a live payload or the actual jar in this session, and the dependency's
  licence is also still unconfirmed (see External Libraries) -- guessing at method
  signatures risked a project-wide compile break for a path nothing exercises today
  (`razorpay.enabled` is `false` in every environment so far). The disabled-path stub
  (synthetic ids, `"active"` status, signature always valid) is fully implemented and is
  what every other class in this feature actually runs against.
- **`app.billing.grace-expiry.batch-size`** (default 20) was added alongside
  `app.billing.reconcile.batch-size` -- the Configuration Parameters block below didn't
  originally name a separate batch size for `expireGrace`, but `background-jobs.md`
  requires every batch job's size to be configuration, not a literal, so one was added
  rather than hardcoding `20` in `SubscriptionScheduler`.
- **Background batch methods are split differently than first described.** Rather than
  one `reconcileBatch(int)`/`expireGraceBatch(int)` method each looping internally,
  `SubscriptionService` exposes `findReconcilableIds`/`reconcileOne` and
  `findGraceExpiredIds`/`expireGraceOne`, and `SubscriptionScheduler` (not the Service)
  owns the loop and per-row try/catch -- mirroring this codebase's actual
  `EmailSender`/`EmailOutboxService`/`EmailSenderFlow` split exactly, so each row commits
  in its own transaction and one bad row can't stop the batch (`background-jobs.md`).
- **`SubscriptionService.insertEvent` runs in its own `REQUIRES_NEW` transaction**
  (self-injected proxy call, same pattern as `UserService.recFailed`) and **lets a
  duplicate's `DataIntegrityViolationException` propagate out of it**, rather than
  catching it inside. This was a real bug caught by the test suite, not a hypothetical:
  catching the exception *inside* the `REQUIRES_NEW` method and returning `false`
  compiles and looks right, but Hibernate marks that method's own transaction
  rollback-only the moment the flush fails -- per the JPA spec, independent of whether
  the Java exception is caught -- so Spring's commit at the end of that (still-caught,
  still-doomed) method throws `UnexpectedRollbackException` instead of silently
  succeeding. The fix: `insertEvent` does the plain insert and lets the exception
  propagate (so Spring rolls that transaction back cleanly, which is what rollback is
  for), and `applyWebhookEvent` -- a different transaction -- catches it at the call
  site instead.
- **Layering gap, flagged rather than silently resolved (mirrors the existing
  `*Repo`-shape gap below):** `spring-backend-standards` wants business logic in a
  `*Flow`, with `*Service` limited to data concerns. This feature instead follows the
  reference doc's own shape (`product-assess-server`'s `SubscriptionService`) and keeps
  the plan-swap orchestration, webhook dispatch, and background transitions on
  `SubscriptionService`; `BillingFlow` stays a thin orgId-resolve-and-map layer with no
  rules of its own. Not fixed here -- flagged for the same reason the `*Repo` gap was.

## Overview

Gives every organization a seat-based subscription: each org has exactly one current
plan, and the plan's `seatLimit` caps how many non-`EXITED` `user` rows the org may have.
A new org auto-starts on the `FREE` plan at signup (no checkout, no gateway call). An
`ORG_ADMIN` can upgrade/downgrade via a Razorpay **Subscription** (recurring, auto-renewing
monthly charge) initiated from `POST /api/billing/subscribe`; Razorpay's own webhook is
the source of truth for renewal/failure events, mirrored locally into one `org_subscription`
row per org plus a diagnostics/idempotency `billing_event` log. A background reconciler
polls Razorpay as a catch-all for any missed webhook, and a second scheduled job downgrades
an org to `FREE` once its post-failure grace period lapses. **The invoice/receipt email is
sent only once Razorpay's webhook confirms the charge** (`subscription.activated` /
`subscription.charged`) -- never optimistically at `POST /api/billing/subscribe` time,
since that call only creates the gateway subscription; the money hasn't moved yet. **A
separate renewal-warning email** goes out the moment a subscription first enters the
`GRACE` state (payment failing), telling the org it will be downgraded to `FREE` on
`graceUntil` unless the payment is resolved -- reuses the same grace-period mechanism
already in the state machine, not a new date column. **A third, downgrade-notice email**
goes out the moment the downgrade to `FREE` actually happens, whichever of the two paths
triggers it (the grace-expiry scheduler, or Razorpay reporting `cancelled`/`completed`
directly) -- both paths route through one shared `downgradeToFree` helper so the notice
can't be sent from one path and forgotten on the other.

Adapts the patterns in `C:\Users\Yamraj\Desktop\leave-frontend\fronted\prompts\features\billing-feature.md`
(distilled from `product-assess-server`'s one-time interview-credit purchases) to a
**recurring** subscription instead: there is no client-callback/webhook/reconciliation
three-way race to settle a single order, because Razorpay's Subscription object -- not a
local order row -- is the thing being settled. What carries over: external gateway behind
one gated service (`razorpay.enabled`), idempotent event processing (via a unique event-id
row, not a conditional `UPDATE`), reconciliation as a catch-all not the primary path, and
diagnostics-only event logging that never breaks the payment/webhook path it's logging.

**Confirmed with the user (2026-09-28):**
- Model: seat-based subscription (plan caps active employee count).
- Gateway: Razorpay, via its Subscriptions API (recurring). `razorpay-java` dependency --
  user asked to add it with placeholder credentials/config now and fill in the real
  key-id/key-secret/webhook-secret/plan-ids/**license confirmation** later; see External
  Libraries.
- Cadence: recurring monthly auto-renewal (not one-time/manual renewal).
- New org default: auto-starts on `FREE`, immediate use, no checkout required.
- Lapse handling: grace period (default 7 days, configurable), then auto-downgrade to
  `FREE`. Existing employees over the new cap are **not** removed; only new employee
  creation is blocked until back under the cap or upgraded again.
- Plan changes: simple swap, no proration -- switching plans cancels any existing Razorpay
  subscription and creates a new one immediately (new billing cycle starts now).
- Plan tiers/prices below are **placeholders** (starter set), not confirmed numbers --
  edit before/at implementation.
- **Enterprise tier, custom pricing, >100 employees.** A fourth plan, `ENTERPRISE`, for
  orgs whose headcount outgrows `GROWTH`'s 100-seat cap. Not self-serve-priced -- see
  *Enterprise / Custom Pricing* below.

**Not yet confirmed -- flagging before implementation, not deciding silently:**
- **Flat per-tier pricing, not metered per-seat pricing.** "Seat-based" here means the
  plan's seat cap gates headcount; the *price* is a flat monthly amount per tier, not
  `price × active-seat-count` synced to Razorpay on every hire/exit. True per-seat metered
  billing needs Razorpay quantity updates on every employee create/deactivate and is
  materially more code (and a different Razorpay object shape) -- flagged here rather than
  assumed. Say now if metered per-seat billing is actually required.
- Whether `HR` (not just `ORG_ADMIN`) may view (not change) the current plan/usage.
  Defaulted to `ORG_ADMIN`-only, matching `OrgController`'s existing gate.

## Edge Cases

- **Authorization.** Every `/api/billing/*` REST endpoint (not the webhook) is
  `@PreAuthorize("hasRole('ORG_ADMIN')")`, matching `OrgController`. `orgId` always comes
  from `requireOrgId()` (the JWT), never a path/body field -- no cross-org billing action
  is reachable by id.
- **Idempotency -- webhook delivery.** Razorpay retries webhooks on a non-2xx/timeout
  response and can deliver the same event more than once. `billing_event.razorpay_event_id`
  is unique; the insert is the idempotency gate -- a duplicate delivery fails the unique
  insert, is caught, and returns `200` without re-applying the state transition. This
  replaces the reference doc's conditional `UPDATE ... WHERE status = 'CREATED'` (there is
  no single order row transitioning once; there is a stream of events, each applied at
  most once).
- **Idempotency -- re-subscribing to the same plan.** `POST /api/billing/subscribe` with
  the org's already-current, already-`ACTIVE` plan code is a no-op that returns current
  state -- it does not create a second Razorpay subscription.
- **Concurrency -- seat-limit race.** `SubscriptionService.assertSeatAvailable` reads the
  active count and compares to the plan's `seatLimit` with no lock. Two concurrent employee
  creates can both pass the check and land one seat over the cap. Accepted, documented
  tradeoff -- matches this codebase's stated no-pessimistic/no-optimistic-locking
  convention; nothing else in this codebase locks a read-then-write count either. Revisit
  only if seat overshoot becomes a real cost problem.
- **Concurrency -- webhook vs. reconciler.** Both can race to update the same
  `org_subscription` row from a stale local view. Not locked either, same reasoning as
  above -- both write the same target state derived from Razorpay's own authoritative
  status, so a lost update just gets corrected on the next reconcile pass, it never
  diverges permanently.
- **Webhook signature.** `POST /api/billing/webhook/razorpay` is `permitAll` (no JWT --
  Razorpay isn't a logged-in user) but every request's `X-Razorpay-Signature` header is
  HMAC-verified against `razorpay.webhook-secret` before the body is trusted. An invalid or
  missing signature is a flat `400`, body never parsed.
- **Grace-period race with a real payment recovering late.** If Razorpay's own retry
  succeeds (a `subscription.charged` webhook) after the local grace scheduler already
  downgraded the org to `FREE`, the late webhook re-activates the plan the customer is
  paying for again (webhook handler doesn't check "did we already give up" -- it always
  applies the gateway's current truth). Documented behavior, not a bug: the org keeps the
  seats they already had over the `FREE` cap in the interim, same as any other over-cap
  state.
- **Existing employees over a new, lower cap.** Never force-deactivated. Only new
  creation (`HrEmpFlow.create`, `EmpImportFlow.importEmployees`) is gated.
- **CSV import partial capacity.** If an import batch would push the org over the seat cap,
  rows beyond remaining capacity are skipped with a per-row reason (reusing
  `EmpImportFlow`'s existing skip-line/`EmpImportResultData` mechanism), not a whole-batch
  rejection -- consistent with how it already handles other per-row rejections.
- **Non-enumeration / sensitive data.** Razorpay webhook payloads carry a payment id, not
  card details -- fine to store the raw payload in `billing_event.payload` for diagnostics.
  Never log `razorpay.key-secret` / `razorpay.webhook-secret`.
- **Gateway failures are caught and logged, never left to bubble up raw.** Every
  `RazorpayGatewayService` method wraps its SDK call in a try/catch for
  `com.razorpay.RazorpayException` (and a catch-all `Exception`), logs the failure at
  `ERROR` with org id / (local) subscription id / the SDK's own error code -- never the
  request payload or `razorpay.key-secret` -- and rethrows a plain `RuntimeException` with
  a generic message. No new exception type is invented: on the request path
  (`POST /api/billing/subscribe`) that surfaces through `GlobalExceptionHandler`'s existing
  catch-all as the standard safe `500`; on a background path (reconcile, webhook) it's
  caught per-row/per-event by the caller (`SubscriptionScheduler`, `BillingWebhookController`)
  so one org's Razorpay error never stops the batch or crashes the webhook handler for
  other events.
- **Mail (Mailjet) failures are already handled by existing infra, not new code.**
  `InvoiceMailer`/`BillingMailer`-style sends go through the existing
  `EmailOutboxService.enqueue` -> `EmailSenderFlow`/`scheduler.EmailSender` pipeline, exactly
  like `InviteMailer`/`VerificationMailer` today: enqueuing is a fast local insert inside the
  webhook's own transaction (can't fail on a slow/unreachable Mailjet relay), and the actual
  SMTP send happens later, with `EmailSenderFlow.markAttemptFailed` already catching and
  logging every failed attempt (retried up to `app.mail.max-attempts`, then `FAILED`). No new
  mail-exception-handling code is needed -- reusing this pipeline *is* the exception handling.
- **Renewal-warning email is sent exactly once per lapse, not once per webhook retry.** It
  fires only on the transition where `graceUntil` is set for the *first* time for a given
  payment failure (see Business Logic) -- a repeated `pending`/`halted` webhook for the same,
  still-open lapse does not re-send it.
- **Signup unaffected.** `AuthFlow.signup` gains exactly one new call
  (`subscriptionSvc.createFreeSubscription`) in the same transaction as today's org/user/
  employee inserts -- no new required signup input, no checkout step for a brand-new org.

## Enterprise / Custom Pricing

- `plan` gains `is_custom_pricing` (boolean, `false` for `FREE`/`STARTER`/`GROWTH`). One new
  seed row, `ENTERPRISE`: `is_custom_pricing = true`, `seat_limit = Integer.MAX_VALUE` (never
  the seat-capping tier), `price_minor = 0` (unused -- no flat price exists to charge),
  `razorpay_plan_id = null`.
- **No self-serve checkout for a custom-priced plan.** `POST /api/billing/subscribe` with
  `planCode = ENTERPRISE` (or any future `is_custom_pricing` plan) never calls
  `RazorpayGatewayService` and never changes `org_subscription` -- it's a `409` ("this plan
  requires a custom quote -- contact sales") so a negotiated price can't be silently
  bypassed by hitting the API. Every other plan's `subscribe` path is unchanged.
- **Assignment is manual, out-of-band, once terms are agreed** -- same "no admin UI for a
  one-off ops action" spirit as this codebase's seeded default `organization` row. Ops
  updates the org's `org_subscription.plan_id` to `ENTERPRISE` directly; no new endpoint or
  UI this pass. Flagged for confirmation: revisit if this needs to become a supported
  `ORG_ADMIN`-visible or sales-tool-driven action later.
- **Discovery, not enforcement.** `GET /api/billing/plans` lists `ENTERPRISE` alongside the
  others (`priceMinor` reads as `0`/not meaningful -- UI shows "Contact us" for any
  `isCustomPricing` plan) so an admin whose headcount exceeds `GROWTH`'s cap can see it's the
  next tier, even though they can't self-serve into it.
- `SubscriptionService.assertSeatAvailable`/`remainingSeats` need no special case: an
  `ENTERPRISE` org's `seat_limit` (`Integer.MAX_VALUE`) already never trips the cap.
- `PlanData` gains `boolean isCustomPricing`.

## API / Interface

All under `/api/billing`, `ORG_ADMIN` only except the webhook.

| Method | Path | Body | Response | Auth |
|---|---|---|---|---|
| GET | `/api/billing/plans` | -- | `PlanData[]` (code, name, seatLimit, priceMinor, currency, billingPeriod) | `ORG_ADMIN` |
| GET | `/api/billing/subscription` | -- | `SubscriptionData` (planCode, planName, seatLimit, seatsUsed, status, currentPeriodEnd, graceUntil, checkoutUrl=null) | `ORG_ADMIN` |
| POST | `/api/billing/subscribe` | `SubscribeForm {planCode}` | `SubscriptionData` (`checkoutUrl` set when a paid plan's Razorpay subscription still needs the admin to authorize payment) | `ORG_ADMIN` |
| POST | `/api/billing/cancel` | -- | `SubscriptionData` (downgraded to `FREE`) | `ORG_ADMIN` |
| POST | `/api/billing/webhook/razorpay` | raw Razorpay event JSON | `{}` (`200` even on a duplicate/ignored event, so Razorpay stops retrying) | public, signature-verified |

## Validation & Access Control

- `SubscribeForm.planCode` required, non-blank; `BillingFlow.subscribe` resolves it via
  `PlanService.findByCodeOrNull` -- unknown code is a `400`, not a `404` (consistent with
  not leaking which codes almost-matched).
- Every `BillingController`/`BillingWebhookController` method logs `orgId`/event id at
  entry, matching `OrgController`'s existing `log.info` convention.
- `BillingWebhookController` verifies `X-Razorpay-Signature` via
  `RazorpayGatewayService.verifySignature(rawBody, signature)` before any parsing --
  boundary validation happens before business logic, per `security-standards`.
- Resource-level check: every `org_subscription` row is loaded by `orgId` (from the JWT),
  never by its own id from a path/body -- there is no "act on subscription id X" endpoint,
  so there's no IDOR surface to check beyond the existing `orgId` scoping.

## Business Logic

- **`PlanService`** (`@Transactional(readOnly = true)`) -- `listActive()` (bounded: the
  plan table is a small, fixed catalog, same carve-out as `leave_type`), `findByCode(String)`
  (throws `NotFoundException`), `findByCodeOrNull(String)`.
- **`SubscriptionService`** (`@Transactional`, `rollbackFor = Exception.class`) --
  - `createFreeSubscription(Long orgId)` -- inserts the org's first `org_subscription` row:
    `planId = FREE`, `status = ACTIVE`, no Razorpay fields. Called once, from
    `AuthFlow.signup`.
  - `getCurrent(Long orgId) -> OrgSubscriptionPojo` (`NotFoundException` if somehow
    missing -- should be unreachable post-signup).
  - `assertSeatAvailable(Long orgId)` -- `activeCount = userRepo.countActiveByOrgId(orgId)`
    vs. current plan's `seatLimit`; `>=` throws `ConflictException` ("seat limit reached for
    your current plan -- upgrade or remove an existing account first"). Called from
    `HrEmpFlow.create` and per-row from `EmpImportFlow.importEmployees`.
  - `remainingSeats(Long orgId) -> int` -- `seatLimit - activeCount`, floored at 0; used by
    `EmpImportFlow` to know how many CSV rows it can still accept before skipping the rest.
  - `subscribe(Long orgId, String planCode)` -- the plan-swap orchestration described in
    Overview: resolves the plan first and rejects `isCustomPricing` plans with `ConflictException`
    ("this plan requires a custom quote -- contact sales") before anything else runs -- see
    *Enterprise / Custom Pricing*; same-plan-already-active is a no-op; switching **to** `FREE` cancels any
    existing Razorpay subscription (via `RazorpayGatewayService`) and sets the local row to
    `FREE`/`ACTIVE`/no Razorpay fields/`graceUntil = null`; switching to any other plan
    cancels any existing Razorpay subscription first, then creates a new one
    (`RazorpayGatewayService.createSubscription`), stores the returned
    `razorpaySubscriptionId`, sets local `status = PENDING` until a webhook confirms
    activation, and returns the gateway's checkout URL for the controller to pass through.
  - `applyWebhookEvent(String eventId, String eventType, String orgSubscriptionRef, String
    rawPayload)` -- inserts the `billing_event` row first (unique `razorpay_event_id` is
    the idempotency gate: catch the constraint violation, log, return -- no state change on
    a duplicate); on a fresh insert, dispatches by `eventType` to update the matching
    `org_subscription` row (found by `razorpaySubscriptionId`):
    - `activated`/`charged` -> `ACTIVE` + refreshed `currentPeriodEnd`, **then**
      `billingMailer.sendInvoice(orgId, plan, priceMinor, currency, currentPeriodEnd)` --
      the confirmed-charge invoice/receipt, sent only here (never from `subscribe`, never
      from `reconcileBatch` -- see Edge Cases).
    - `pending`/`halted` -> `GRACE` + `graceUntil = now + app.billing.grace-period-days`,
      but **only when `graceUntil` was previously `null`** (first sighting of this lapse --
      do not push the deadline out on a second `pending`/`halted` for the same cycle); that
      same first-time transition also calls
      `billingMailer.sendRenewalWarning(orgId, plan, graceUntil)`. A repeat `pending`/
      `halted` for an already-`GRACE` row updates nothing and sends nothing.
    - `cancelled`/`completed` -> downgrade to `FREE` immediately (skips waiting out any
      grace window -- these mean the subscription is truly over, not retrying), **then**
      `billingMailer.sendDowngradeNotice(orgId, oldPlan)` -- same downgrade-notice call the
      grace-expiry path below makes; factored into one private helper
      (`downgradeToFree(orgId, reason)`) so both transition sites can't drift apart.
  - **Background methods** (called only from `scheduler.SubscriptionScheduler`, batches of
    20 per `jpa-persistence.md` §15):
    - `reconcileBatch(int batchSize)` -- rows with `razorpaySubscriptionId IS NOT NULL` and
      `status IN (PENDING, ACTIVE, GRACE)`, oldest-`updatedAt`-first; re-fetches each from
      Razorpay and applies the same transition logic `applyWebhookEvent` uses, so a missed
      webhook is caught within one poll interval. Reuses the same `downgradeToFree` helper
      (and so also sends the downgrade notice) when Razorpay's own state has already moved
      to `cancelled`/`completed` by the time this poll runs.
    - `expireGraceBatch(int batchSize)` -- rows with `status = GRACE` and
      `graceUntil < now`, oldest-first; calls `downgradeToFree(orgId, "grace period
      expired")` for each -- downgrades to `FREE`/`ACTIVE`, clears Razorpay fields and
      `graceUntil`, and sends the downgrade-notice email once per row.
- **`RazorpayGatewayService`** -- the one class that imports `com.razorpay.*`. Gated by
  `razorpay.enabled` (default `false`, matching the reference doc's dev/test stub
  pattern): when `false`, every method returns a synthetic, immediately-`activated` result
  so dev/test never needs real Razorpay credentials. When `true`: `createSubscription`,
  `cancelSubscription`, `fetchSubscription`, `verifySignature` call the real SDK. No caller
  outside this class ever sees a Razorpay SDK type.
- **`SubscriptionScheduler`** (`@Component`, mirrors `EmailSender`'s
  thin-scheduler-delegates-to-transactional-method shape; single-WAR/no leader-lease, same
  as `EmailSender`) -- two `@Scheduled` methods, `reconcile()` and `expireGrace()`, each
  calling the matching `SubscriptionService` batch method and logging every exception (never
  let one bad row stop the poll loop).
- **`BillingFlow`** -- thin orchestration only (per `layered-flow.md`): resolves
  `orgId`/role already done by the controller, calls `PlanService`/`SubscriptionService`,
  maps `*Pojo` -> `*Data`. Holds no business rules itself.
- **`BillingMailer`** -- one class, three methods, following the existing
  `InviteMailer`/`VerificationMailer`/`PasswordResetMailer` shape exactly (build subject +
  plain/HTML body, `outboxSvc.enqueue(...)`, return -- never calls `MailService` directly).
  Recipients are every `ACTIVE`/`ON_NOTICE` `ORG_ADMIN` user in the org
  (`userSvc.findByOrgIdAndRoleIn(orgId, Set.of(ORG_ADMIN))`, already exists on `UserRepo`) --
  one outbox row per recipient, `userId` set to that admin's id (log-correlation field, per
  `EmailOutboxPojo`'s existing convention).
  - `sendInvoice(orgId, plan, priceMinor, currency, periodEnd)` -- the payment-confirmed
    receipt. `EmailType.INVOICE`.
  - `sendRenewalWarning(orgId, plan, graceUntil)` -- "your payment failed, you'll be moved
    to Free on `graceUntil` unless it's resolved". `EmailType.RENEWAL_WARNING`.
  - `sendDowngradeNotice(orgId, oldPlan)` -- "you've been moved to Free" -- fired by
    `downgradeToFree` regardless of which path triggered it (grace expired, or Razorpay
    reported `cancelled`/`completed`). `EmailType.DOWNGRADE_NOTICE`.
  `model.enums.EmailType` gains `INVOICE`, `RENEWAL_WARNING`, `DOWNGRADE_NOTICE` (currently
  `VERIFICATION, INVITE, APPROVAL, PASSWORD_RESET` -- diagnostic-only values, nothing
  branches on them, per that enum's existing comment).

## Data Models

**`PlanPojo`** -- table `plan`. Global reference/catalog data (not per-org), same "seed
data, HR/ops-configured" spirit as `leave_type` but organization-wide, not per-tenant.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | bigint | no | `@TableGenerator` |
| `code` | varchar(32) | no | unique (global); `FREE`, `STARTER`, `GROWTH` (placeholders) |
| `name` | varchar(64) | no | display name |
| `seat_limit` | int | no | max active (`status <> EXITED`) `user` rows the org may have on this plan |
| `price_minor` | bigint | no | flat price per billing period, minor units (paise); `0` for `FREE` |
| `currency` | varchar(3) | no | ISO 4217, e.g. `INR` |
| `billing_period` | varchar(16) | no | `@Enumerated(STRING)` -> `BillingPeriod`: `MONTHLY` (only value used by the placeholder plans; `ANNUAL` reserved) |
| `razorpay_plan_id` | varchar(64) | yes | Razorpay's own Plan id; `null` for `FREE` (never charged) |
| `is_custom_pricing` | boolean | no | `true` only for `ENTERPRISE` -- blocks self-serve `subscribe`, see *Enterprise / Custom Pricing* |
| `is_active` | boolean | no | soft-disable a tier without deleting history that references it |
| `created_at` | timestamp | no, `updatable=false` | |

Placeholder starter rows (seed manually or via a small startup check, same spirit as
`organization`'s seeded default row -- exact seeding mechanism decided at implementation
time): `FREE` (5 seats, ₹0), `STARTER` (25 seats, ₹999.00/mo), `GROWTH` (100 seats,
₹2999.00/mo), `ENTERPRISE` (unbounded seats, custom-quoted, `is_custom_pricing = true`) --
**edit before/at implementation**.

**`OrgSubscriptionPojo`** -- table `org_subscription`. 1:1 with `organization`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | bigint | no | `@TableGenerator` |
| `org_id` | bigint | no, unique | plain FK column, no `@ManyToOne` -- one row per org |
| `plan_id` | bigint | no | plain FK column -> `plan.id` -- current effective plan, live-joined (not snapshotted): a later edit to a plan's `seat_limit` applies to existing subscribers immediately, same as `leave_type` config changes apply to existing leave requests. Flagged for confirmation: this differs from the reference doc's "rate frozen per-row" pattern, deliberately -- `seat_limit` is an operational cap, not a financial/legal figure needing a point-in-time snapshot |
| `status` | varchar(16) | no | `@Enumerated(STRING)` -> `SubscriptionStatus`: `PENDING` (Razorpay subscription created, awaiting the admin's checkout authorization), `ACTIVE`, `GRACE` (payment failing, within the configured grace window), `CANCELLED` (terminal, briefly, before the same transaction downgrades to `FREE`/`ACTIVE` -- see Business Logic) |
| `razorpay_subscription_id` | varchar(64) | yes, unique | `null` while on `FREE`; unique when present (MySQL allows multiple `NULL`s under a unique index, so many `FREE` orgs coexist) |
| `current_period_end` | timestamp | yes | from the gateway; `null` on `FREE` |
| `grace_until` | timestamp | yes | set when a `pending`/`halted` webhook first lands; cleared on recovery or on downgrade |
| `created_at` | timestamp | no, `updatable=false` | |
| `updated_at` | timestamp | no | bumped on every state transition (webhook, reconcile, subscribe, grace-expiry) |

**`BillingEventPojo`** -- table `billing_event`. Diagnostics + webhook-idempotency log,
never read by any business-logic path except the dedup insert itself -- matches the
reference doc's "audit log is diagnostics-only" pattern, but here the log entry *is* the
idempotency mechanism (the unique constraint), not a bystander to it.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | bigint | no | `@TableGenerator` |
| `razorpay_event_id` | varchar(64) | no, unique | Razorpay's own event id -- the dedup key |
| `event_type` | varchar(64) | no | e.g. `subscription.activated`, open-ended (Razorpay's own event names), plain `String` not enum -- same reasoning as `notification.type` |
| `org_id` | bigint | yes | resolved from the payload where possible; `null` if the event doesn't map to a known `org_subscription` row (logged, not fatal) |
| `payload` | text | yes | raw event JSON snapshot, for diagnostics only |
| `processed_at` | timestamp | no | |
| `created_at` | timestamp | no, `updatable=false` | |

**`SubscribeForm`** -- `{ String planCode }`.

**`PlanData`** -- `{ String code, name; int seatLimit; long priceMinor; String currency;
BillingPeriod billingPeriod }`.

**`SubscriptionData`** -- `{ String planCode, planName; int seatLimit, seatsUsed;
SubscriptionStatus status; Instant currentPeriodEnd, graceUntil; String checkoutUrl }`.

**New enums** (`model.enums`, own files): `BillingPeriod { MONTHLY, ANNUAL }`,
`SubscriptionStatus { PENDING, ACTIVE, GRACE, CANCELLED }`.

## Database Indexes & Constraints

- `plan`: `unique(code)` (also serves any `code` lookup -- no separate index needed).
  Small fixed catalog (a handful of rows) -- no other index justified.
- `org_subscription`: `unique(org_id)` (also serves `SubscriptionService.getCurrent`'s
  lookup). `unique(razorpay_subscription_id)` (serves the webhook/reconcile lookup by
  gateway id; MySQL permits multiple `NULL`s so `FREE` orgs don't collide). No index on
  `plan_id` alone -- the table has at most one row per org, and every read goes through
  `org_id`; a `plan_id` filter has no caller. `status`/`grace_until` are not indexed either
  for the same reason: `reconcileBatch`/`expireGraceBatch` scan a table sized to the
  organization count, not an unbounded event stream -- flagged here for confirmation rather
  than added speculatively; revisit if org count grows large enough that the scan shows up
  in slow-query logs.
- `billing_event`: `unique(razorpay_event_id)` (the idempotency check *is* this index). No
  index on `org_id` -- nothing reads `billing_event` by org yet (no "billing history"
  endpoint in this pass); add one if that endpoint is built later.
- No existing index becomes redundant -- these are three new tables.

## Data Access

- **`PlanRepo`** (`JpaRepository<PlanPojo, Long>`, matching this project's existing
  convention -- see note below) -- `findByCode(String) -> Optional<PlanPojo>`,
  `findByIsActiveTrue() -> List<PlanPojo>` (bounded: fixed small catalog).
- **`OrgSubscriptionRepo`** -- `findByOrgId(Long) -> Optional<OrgSubscriptionPojo>`,
  `findByRazorpaySubscriptionId(String) -> Optional<OrgSubscriptionPojo>`,
  `findReconcilable(int limit) -> List<...>` (JPQL: `razorpaySubscriptionId IS NOT NULL AND
  status IN (...) ORDER BY updatedAt ASC`, capped), `findGraceExpired(Instant now, int
  limit) -> List<...>` (JPQL: `status = GRACE AND graceUntil < :now ORDER BY graceUntil
  ASC`, capped).
- **`BillingEventRepo`** -- `existsByRazorpayEventId(String) -> boolean` (or rely on the
  unique-constraint violation directly -- decide at implementation time whichever reads
  cleaner at the one call site), plain `save`.
- **`UserRepo`** gains `countActiveByOrgId(Long orgId) -> long` (JPQL:
  `SELECT COUNT(u) FROM UserPojo u WHERE u.orgId = :orgId AND u.status <> 'EXITED'`).

**Convention note, not a change this feature makes:** `spring-backend-standards`
(`coding.md` §8) calls for a plain class over `AbstractRepo`/hand-written JPQL, never
`JpaRepository`. This codebase's *every existing* `*Repo` (`OrgRepo`, `UserRepo`, `EmpRepo`,
...) is already a `JpaRepository` interface with `@Query`/`@Param`, and `backend/CLAUDE.md`
does not list this as a deliberate override. This plan follows the codebase's actual,
consistent existing convention rather than introducing the one new-style repo that would
be inconsistent with all the others -- flagging the gap between rulebook and reality here
rather than silently either following or fixing it.

## Background Processing

- **`SubscriptionScheduler.reconcile()`** -- `@Scheduled(fixedDelayString =
  "${app.billing.reconcile.poll-delay-ms}")`. Catch-all for a missed webhook, not the
  primary path -- batches of 20 (`app.billing.reconcile.batch-size`), oldest-updated-first.
- **`SubscriptionScheduler.expireGrace()`** -- `@Scheduled(fixedDelayString =
  "${app.billing.grace-expiry.poll-delay-ms}")`. Downgrades any org whose grace window has
  lapsed. Batches of 20, oldest-`graceUntil`-first.
- Both single-instance, no leader lease -- same reasoning as the existing `EmailSender`
  (one WAR, one Tomcat).

## External Libraries

- **`com.razorpay:razorpay-java`** -- Razorpay's official Java SDK (Subscriptions API +
  webhook signature verification). Per your instruction, adding it now with placeholder
  version/config; **license not yet verified in this session** (a check was in progress and
  you asked to defer it) -- confirm the license (must be permissive -- Apache 2.0/MIT/BSD/
  ISC per `security-standards`) before this merges, and record it in
  `prompts/libraries.md` once confirmed.

## Configuration Parameters

```properties
razorpay.enabled=false
razorpay.key-id=
razorpay.key-secret=
razorpay.webhook-secret=
app.billing.grace-period-days=7
app.billing.reconcile.batch-size=20
app.billing.reconcile.poll-delay-ms=900000
app.billing.grace-expiry.poll-delay-ms=3600000
```

`razorpay.key-id`/`key-secret`/`webhook-secret` are blank by default (same pattern as
`db.password`) -- real values come from environment variables in every real deployment,
never committed. `razorpay.enabled=false` means `RazorpayGatewayService`'s synthetic stub
runs everywhere until real credentials are supplied -- dev/test never need them.

## Relevant Files

New:
- `model/enums/BillingPeriod.java`, `model/enums/SubscriptionStatus.java`
- `pojo/PlanPojo.java`, `pojo/OrgSubscriptionPojo.java`, `pojo/BillingEventPojo.java`
- `repo/PlanRepo.java`, `repo/OrgSubscriptionRepo.java`, `repo/BillingEventRepo.java`
- `service/PlanService.java`, `service/SubscriptionService.java`,
  `service/RazorpayGatewayService.java`, `service/BillingMailer.java`
- `workflow/BillingFlow.java`
- `rest/BillingController.java`, `rest/BillingWebhookController.java`
- `scheduler/SubscriptionScheduler.java`
- `model/form/SubscribeForm.java`
- `model/data/PlanData.java`, `model/data/SubscriptionData.java`
- Matching `*RepoTest`/`*TestData` classes per `jpa-persistence.md`'s repository test
  convention.

Modified:
- `pom.xml` -- add `razorpay-java` (placeholder version until confirmed)
- `prompts/libraries.md` -- add `razorpay-java` once its license is confirmed
- `config/SecurityConfig.java` -- `permitAll` for `POST /api/billing/webhook/razorpay`
- `workflow/AuthFlow.java` -- `signup` calls `subscriptionSvc.createFreeSubscription`
- `workflow/HrEmpFlow.java` -- `create` calls `subscriptionSvc.assertSeatAvailable` first
- `workflow/EmpImportFlow.java` -- per-row seat-cap skip using
  `subscriptionSvc.remainingSeats`
- `repo/UserRepo.java` -- add `countActiveByOrgId`
- `model/enums/EmailType.java` -- add `INVOICE`, `RENEWAL_WARNING`, `DOWNGRADE_NOTICE`
- `src/main/resources/app.properties` / `test.properties` -- new config keys above
- `prompts/schema.md` -- add `plan`/`org_subscription`/`billing_event` (post-implementation)
- `backend/CLAUDE.md` "What's built so far" (post-implementation)
