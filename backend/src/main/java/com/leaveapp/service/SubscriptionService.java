package com.leaveapp.service;

// import flow (layering: Service -> Repo | Service | External Libraries):
// SubscriptionService -> OrgSubscriptionRepo  -> DB (the org's one subscription row)
// SubscriptionService -> BillingEventRepo     -> DB (webhook idempotency insert)
// SubscriptionService -> PlanService          -> DB (plan catalog lookups)
// SubscriptionService -> UserService          -> DB (active-headcount seat math)
// SubscriptionService -> RazorpayGatewayService -> Razorpay (gated by razorpay.enabled)
// SubscriptionService -> BillingMailer        -> email_outbox (queued, never sent inline)
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.pojo.BillingEventPojo;
import com.leaveapp.pojo.OrgSubscriptionPojo;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.repo.BillingEventRepo;
import com.leaveapp.repo.OrgSubscriptionRepo;

// Holds the plan-swap orchestration, webhook dispatch, and the two background batch
// methods -- see prompts/features/billing-subscription.md, Business Logic. This
// mirrors that doc's reference pattern (product-assess-server's own SubscriptionService),
// which is deliberately not split into a separate *Flow: BillingFlow stays a thin
// orgId-resolve-and-map layer (per layered-flow.md) and every actual rule lives here,
// same gap already flagged for this codebase's *Repo shape (JpaRepository vs
// AbstractRepo) -- flagging it again here rather than silently picking a side.
@Service
public class SubscriptionService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final Set<SubscriptionStatus> RECONCILABLE =
        EnumSet.of(SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE);

    @Autowired
    private OrgSubscriptionRepo subRepo;

    @Autowired
    private BillingEventRepo eventRepo;

    @Autowired
    private PlanService planSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private RazorpayGatewayService razorpaySvc;

    @Autowired
    private BillingMailer billingMailer;

    // Self-injected so insertEventIfNew's REQUIRES_NEW actually opens a new physical
    // transaction -- same reasoning as CredentialService.recFailed: a plain `this.` call
    // would bypass the Spring proxy and run in the caller's transaction, which is
    // exactly what would leave the caller's Hibernate session unusable after a caught
    // constraint violation.
    @Autowired
    @Lazy
    private SubscriptionService self;

    @Value("${app.billing.grace-period-days}")
    private int gracePeriodDays;

    /* Public Methods */

    // Called once, from AuthFlow.signup, in the same transaction as the org/user/
    // employee inserts.
    @Transactional(rollbackFor = Exception.class)
    public void createFreeSubscription(Long orgId) {
        PlanPojo free = planSvc.findByCode(PlanCode.FREE);
        OrgSubscriptionPojo sub = new OrgSubscriptionPojo();
        sub.setOrgId(orgId);
        sub.setPlanId(free.getId());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        Instant now = Instant.now();
        sub.setCreatedAt(now);
        sub.setUpdatedAt(now);
        subRepo.save(sub);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public OrgSubscriptionPojo getCurrent(Long orgId) {
        return subRepo.findByOrgId(orgId) //
            .orElseThrow(() -> new NotFoundException("subscription not found orgId=" + orgId));
    }

    // Called from HrEmpFlow.create -- no lock (see the feature doc's documented
    // seat-limit race: an accepted, matches-this-codebase's-convention tradeoff).
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public void assertSeatAvailable(Long orgId) {
        OrgSubscriptionPojo sub = getCurrent(orgId);
        PlanPojo plan = planSvc.findById(sub.getPlanId());
        long active = userSvc.countActiveByOrgId(orgId);
        if (active >= plan.getSeatLimit()) {
            throw new ConflictException(
                "seat limit reached for your current plan -- upgrade or remove an existing account first");
        }
    }

    // Used by EmpImportFlow to know how many CSV rows it can still accept before
    // skipping the rest, per-row, with a reason.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public int remainingSeats(Long orgId) {
        OrgSubscriptionPojo sub = getCurrent(orgId);
        PlanPojo plan = planSvc.findById(sub.getPlanId());
        long active = userSvc.countActiveByOrgId(orgId);
        long remaining = plan.getSeatLimit() - active;
        return (int) Math.max(0, remaining);
    }

    // The plan-swap orchestration: same-plan-already-active is a no-op; switching to a
    // free (priceMinor == 0) plan cancels any existing Razorpay subscription and sets
    // the local row directly; switching to any other plan cancels first, then creates a
    // new Razorpay subscription and leaves the row PENDING until a webhook confirms it.
    @Transactional(rollbackFor = Exception.class)
    public SubscribeResult subscribe(Long orgId, PlanCode planCode) {
        PlanPojo plan = planSvc.findByCode(planCode);
        if (plan.isCustomPricing()) {
            throw new ConflictException("this plan requires a custom quote -- contact sales");
        }
        OrgSubscriptionPojo sub = getCurrent(orgId);
        if (plan.getId().equals(sub.getPlanId()) && SubscriptionStatus.ACTIVE == sub.getStatus()) {
            return new SubscribeResult(sub, null); // already on this plan -- no-op, no second Razorpay subscription
        }
        // A paid, in-good-standing org can only upgrade. Downgrade/cancel is reached only via
        // GRACE (payment failing) or when the subscription ends (downgradeToFree), never here.
        long currentPrice = planSvc.findById(sub.getPlanId()).getPriceMinor();
        if (SubscriptionStatus.ACTIVE == sub.getStatus() && currentPrice > 0 && plan.getPriceMinor() <= currentPrice) {
            throw new ConflictException("active paid plan can only be upgraded; downgrade happens when it ends");
        }
        if (sub.getRazorpaySubscriptionId() != null) {
            razorpaySvc.cancelSubscription(sub.getRazorpaySubscriptionId());
        }
        if (plan.getPriceMinor() == 0) {
            sub.setPlanId(plan.getId());
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setRazorpaySubscriptionId(null);
            sub.setPendingPlanId(null);
            sub.setCurrentPeriodEnd(null);
            sub.setGraceUntil(null);
            log.info("org switched to free plan orgId={} planCode={}", orgId, plan.getCode());
            return new SubscribeResult(sub, null);
        }
        RazorpaySubResult rzp = razorpaySvc.createSubscription(plan.getRazorpayPlanId());
        // planId/status stay as they are: the new plan only takes effect once Razorpay
        // confirms payment (handleActivated), so closing the checkout popup changes nothing.
        sub.setPendingPlanId(plan.getId());
        sub.setRazorpaySubscriptionId(rzp.subscriptionId());
        log.info("org subscribing orgId={} planCode={} razorpaySubId={}", orgId, plan.getCode(), rzp.subscriptionId());
        return new SubscribeResult(sub, rzp.checkoutUrl());
    }

    // Called from BillingWebhookController once the signature has already verified.
    // razorpaySubId resolves the local row; a duplicate delivery's eventId fails the
    // insert below and returns without re-applying the transition.
    @Transactional(rollbackFor = Exception.class)
    public void applyWebhookEvent(String eventId, String eventType, String razorpaySubId, String rawPayload) {
        OrgSubscriptionPojo sub = subRepo.findByRazorpaySubscriptionId(razorpaySubId).orElse(null);
        boolean fresh;
        try {
            self.insertEvent(eventId, eventType, sub == null ? null : sub.getOrgId(), rawPayload);
            fresh = true;
        } catch (DataIntegrityViolationException e) {
            fresh = false;
        }
        if (!fresh) {
            log.info("duplicate billing webhook event eventId={}", eventId);
            return;
        }
        if (sub == null) {
            log.warn("billing webhook event for unknown subscription razorpaySubId={} eventType={}",
                razorpaySubId, eventType);
            return;
        }
        dispatchWebhookEvent(sub, eventType);
    }

    // Its own (REQUIRES_NEW) transaction -- the unique-constraint insert IS the
    // idempotency gate (see BillingEventPojo). The constraint violation is left to
    // PROPAGATE, not caught here: per the JPA spec a failed flush marks its owning
    // transaction rollback-only regardless of whether the Java exception is caught, so
    // swallowing it here would still leave this REQUIRES_NEW transaction unable to
    // commit (a caught-but-still-poisoned transaction raises Spring's own
    // UnexpectedRollbackException at commit time -- this surfaced as a real failure
    // while writing this method, not a hypothetical one). Rolling back cleanly and
    // catching the exception at the caller, in an entirely different transaction, is
    // what actually works.
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insertEvent(String eventId, String eventType, Long orgId, String rawPayload) {
        BillingEventPojo event = new BillingEventPojo();
        event.setRazorpayEventId(eventId);
        event.setEventType(eventType);
        event.setOrgId(orgId);
        event.setPayload(rawPayload);
        Instant now = Instant.now();
        event.setProcessedAt(now);
        event.setCreatedAt(now);
        eventRepo.saveAndFlush(event);
    }

    // Bounded id list for SubscriptionScheduler.reconcile -- each id is then processed
    // by reconcileOne in its own transaction, per background-jobs.md.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<Long> findReconcilableIds(int limit) {
        return subRepo.findReconcilable(RECONCILABLE, PageRequest.of(0, limit)).stream() //
            .map(OrgSubscriptionPojo::getId) //
            .toList();
    }

    // Re-fetches one row's authoritative status from Razorpay and applies the same
    // transition logic the webhook path uses -- catch-all for a missed webhook.
    @Transactional(rollbackFor = Exception.class)
    public void reconcileOne(Long subscriptionId) {
        OrgSubscriptionPojo sub = subRepo.findById(subscriptionId).orElse(null);
        if (sub == null || sub.getRazorpaySubscriptionId() == null) {
            return;
        }
        RazorpaySubStatus status = razorpaySvc.fetchSubscription(sub.getRazorpaySubscriptionId());
        applyGatewayStatus(sub, status.status(), status.currentPeriodEnd());
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<Long> findGraceExpiredIds(int limit) {
        return subRepo.findGraceExpired(Instant.now(), PageRequest.of(0, limit)).stream() //
            .map(OrgSubscriptionPojo::getId) //
            .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void expireGraceOne(Long subscriptionId) {
        OrgSubscriptionPojo sub = subRepo.findById(subscriptionId).orElse(null);
        if (sub == null || SubscriptionStatus.GRACE != sub.getStatus()) {
            return; // already recovered or downgraded since this id was listed
        }
        downgradeToFree(sub, "grace period expired");
    }

    /* Private Helper Methods */

    private void dispatchWebhookEvent(OrgSubscriptionPojo sub, String eventType) {
        if (eventType.endsWith(".activated") || eventType.endsWith(".charged")) {
            // Re-fetch rather than trust the webhook body for the renewed period end --
            // this is also what makes reconcileOne able to reuse the exact same
            // transition logic below with no separate "from a webhook" code path.
            RazorpaySubStatus status = razorpaySvc.fetchSubscription(sub.getRazorpaySubscriptionId());
            applyGatewayStatus(sub, status.status(), status.currentPeriodEnd());
        } else if (eventType.endsWith(".pending") || eventType.endsWith(".halted")) {
            handleGrace(sub);
        } else if (eventType.endsWith(".cancelled") || eventType.endsWith(".completed")) {
            endGatewaySubscription(sub, "razorpay reported " + eventType);
        } else {
            log.info("unhandled billing webhook event type={} orgId={}", eventType, sub.getOrgId());
        }
    }

    private void applyGatewayStatus(OrgSubscriptionPojo sub, String gatewayStatus, Instant gatewayPeriodEnd) {
        switch (gatewayStatus) {
            case "active": //
                handleActivated(sub, gatewayPeriodEnd);
                break;
            case "pending": //
            case "halted": //
                handleGrace(sub);
                break;
            case "cancelled": //
            case "completed": //
                endGatewaySubscription(sub, "razorpay status " + gatewayStatus);
                break;
            default:
                log.info("unhandled razorpay subscription status={} orgId={}", gatewayStatus, sub.getOrgId());
        }
    }

    // Only a genuinely new period -- a real charge advancing currentPeriodEnd, or the
    // first activation out of PENDING -- sends the invoice email. Re-observing an
    // already-current ACTIVE row on a later reconcile poll must not re-mail the same
    // receipt (reconcile has no discrete event id to dedupe against like the webhook
    // path does, so the guard has to be a state comparison instead).
    private void handleActivated(OrgSubscriptionPojo sub, Instant gatewayPeriodEnd) {
        boolean newPeriod = sub.getCurrentPeriodEnd() == null
            || (gatewayPeriodEnd != null && gatewayPeriodEnd.isAfter(sub.getCurrentPeriodEnd()));
        boolean promoting = sub.getPendingPlanId() != null;
        if (!newPeriod && !promoting && SubscriptionStatus.ACTIVE == sub.getStatus()) {
            return;
        }
        if (promoting) {
            sub.setPlanId(sub.getPendingPlanId());
            sub.setPendingPlanId(null);
        }
        PlanPojo plan = planSvc.findById(sub.getPlanId());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        if (gatewayPeriodEnd != null) {
            sub.setCurrentPeriodEnd(gatewayPeriodEnd);
        }
        sub.setGraceUntil(null);
        billingMailer.sendInvoice(sub.getOrgId(), plan, plan.getPriceMinor(), plan.getCurrency(),
            sub.getCurrentPeriodEnd());
    }

    // A never-paid checkout (pendingPlanId set) that Razorpay cancels/expires just drops
    // the pending upgrade; the org keeps the plan it already had.
    private void endGatewaySubscription(OrgSubscriptionPojo sub, String reason) {
        if (sub.getPendingPlanId() == null) {
            downgradeToFree(sub, reason);
            return;
        }
        sub.setPendingPlanId(null);
        sub.setRazorpaySubscriptionId(null);
        log.info("pending upgrade abandoned orgId={} reason={}", sub.getOrgId(), reason);
    }

    // graceUntil is set only on the FIRST sighting of a lapse -- a repeated
    // pending/halted for an already-GRACE row updates nothing and sends nothing,
    // matching the feature doc's "sent exactly once per lapse" edge case.
    // A pending upgrade has not been charged yet, so there is nothing to lapse.
    private void handleGrace(OrgSubscriptionPojo sub) {
        if (sub.getGraceUntil() != null || sub.getPendingPlanId() != null) {
            return;
        }
        PlanPojo plan = planSvc.findById(sub.getPlanId());
        Instant until = Instant.now().plus(gracePeriodDays, ChronoUnit.DAYS);
        sub.setStatus(SubscriptionStatus.GRACE);
        sub.setGraceUntil(until);
        billingMailer.sendRenewalWarning(sub.getOrgId(), plan, until);
    }

    // Shared by both downgrade paths (grace-expiry scheduler, Razorpay reporting
    // cancelled/completed directly) so the notice email can't be sent from one and
    // forgotten on the other.
    private void downgradeToFree(OrgSubscriptionPojo sub, String reason) {
        PlanPojo oldPlan = planSvc.findById(sub.getPlanId());
        PlanPojo freePlan = planSvc.findByCode(PlanCode.FREE);
        sub.setPlanId(freePlan.getId());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setRazorpaySubscriptionId(null);
        sub.setPendingPlanId(null);
        sub.setCurrentPeriodEnd(null);
        sub.setGraceUntil(null);
        log.info("org downgraded to free orgId={} reason={}", sub.getOrgId(), reason);
        billingMailer.sendDowngradeNotice(sub.getOrgId(), oldPlan);
    }
}
