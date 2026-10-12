package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.pojo.OrgSubscriptionPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.EmailOutboxRepo;
import com.leaveapp.repo.OrgSubscriptionRepo;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.repo.UserTestData;

// Fills the gaps SubscriptionServiceTest leaves: every plan-swap guard, every webhook
// event-type arm, every gateway-status arm (driven through reconcileOne), the
// handleActivated/handleGrace/endGatewaySubscription state guards, and the batch
// finders. The Razorpay gateway is swapped for a scripted double for the duration of
// each test; BillingMailer stays real so invoice/warning/notice mail is asserted on the
// email_outbox rows it queues for the org's ORG_ADMIN. Each test uses a fresh orgId and
// Razorpay subscription id so the unique constraints never collide across cases.
public class SubscriptionServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    private static final Instant PERIOD = Instant.parse("2031-03-01T00:00:00Z");

    @Autowired
    private SubscriptionService subSvc;

    @Autowired
    private PlanService planSvc;

    @Autowired
    private OrgSubscriptionRepo subRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private EmailOutboxRepo outboxRepo;

    private final ScriptedGateway gateway = new ScriptedGateway();
    private RazorpayGatewayService originalGateway;
    private Long orgId;
    private UserPojo admin;

    /* Public Methods */

    @BeforeEach
    public void swapGatewayAndSeedAdmin() {
        originalGateway = (RazorpayGatewayService) ReflectionTestUtils.getField(subSvc, "razorpaySvc");
        ReflectionTestUtils.setField(subSvc, "razorpaySvc", gateway);
        orgId = System.nanoTime();
        admin = UserTestData.aUser(orgId, "admin" + orgId + "@test.com");
        admin.setRole(UserRole.ORG_ADMIN);
        userRepo.saveAndFlush(admin);
    }

    @AfterEach
    public void restoreGateway() {
        ReflectionTestUtils.setField(subSvc, "razorpaySvc", originalGateway);
    }

    // ---- subscribe ----

    @Test
    public void subscribe_samePlanButNotActive_fallsThroughNoopGuardAndReactivatesFree() {
        seed(PlanCode.FREE, SubscriptionStatus.PENDING, null, null, null, null);

        OrgSubscriptionPojo result = subSvc.subscribe(orgId, PlanCode.FREE).sub();

        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertTrue(gateway.cancelled.isEmpty());
    }

    @Test
    public void subscribe_activePaidToFree_throwsConflict() {
        seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        assertThrows(ConflictException.class, () -> subSvc.subscribe(orgId, PlanCode.FREE).sub());
        assertTrue(gateway.cancelled.isEmpty());
    }

    @Test
    public void subscribe_activePaidToCheaperPaid_throwsConflict() {
        seed(PlanCode.GROWTH, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        assertThrows(ConflictException.class, () -> subSvc.subscribe(orgId, PlanCode.STARTER).sub());
    }

    @Test
    public void subscribe_activePaidUpgrade_cancelsOldGatewaySubAndLeavesNewOnePending() {
        String oldId = rzpId();
        seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, oldId, null, PERIOD, null);

        SubscribeResult res = subSvc.subscribe(orgId, PlanCode.GROWTH);
        OrgSubscriptionPojo result = res.sub();

        assertEquals(List.of(oldId), gateway.cancelled);
        assertEquals(PlanCode.STARTER, planSvc.findById(result.getPlanId()).getCode());
        assertEquals(PlanCode.GROWTH, planSvc.findById(result.getPendingPlanId()).getCode());
        assertEquals(ScriptedGateway.NEW_SUB_ID, result.getRazorpaySubscriptionId());
        assertEquals(ScriptedGateway.CHECKOUT_URL, res.checkoutUrl());
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
    }

    @Test
    public void subscribe_graceToFree_cancelsGatewaySubAndClearsAllPaidState() {
        String oldId = rzpId();
        seed(PlanCode.STARTER, SubscriptionStatus.GRACE, oldId, null, PERIOD, Instant.now().plus(3, ChronoUnit.DAYS));

        OrgSubscriptionPojo result = subSvc.subscribe(orgId, PlanCode.FREE).sub();

        assertEquals(List.of(oldId), gateway.cancelled);
        assertEquals(PlanCode.FREE, planSvc.findById(result.getPlanId()).getCode());
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertNull(result.getRazorpaySubscriptionId());
        assertNull(result.getPendingPlanId());
        assertNull(result.getCurrentPeriodEnd());
        assertNull(result.getGraceUntil());
    }

    @Test
    public void subscribe_graceToDifferentPaidPlan_isAllowed() {
        seed(PlanCode.STARTER, SubscriptionStatus.GRACE, rzpId(), null, PERIOD, Instant.now().plus(3, ChronoUnit.DAYS));

        OrgSubscriptionPojo result = subSvc.subscribe(orgId, PlanCode.STARTER).sub();

        // same plan but GRACE is not the no-op case: a fresh checkout is created.
        assertEquals(PlanCode.STARTER, planSvc.findById(result.getPendingPlanId()).getCode());
        assertEquals(ScriptedGateway.NEW_SUB_ID, result.getRazorpaySubscriptionId());
    }

    // ---- webhook dispatch ----

    @Test
    public void applyWebhookEvent_charged_refetchesAndRenewsPeriodWithInvoice() {
        String id = rzpId();
        seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, id, null, PERIOD, null);
        Instant renewed = PERIOD.plus(30, ChronoUnit.DAYS);
        gateway.status = new RazorpaySubStatus("active", renewed);

        webhook(id, "subscription.charged");

        assertEquals(renewed, subRepo.findByOrgId(orgId).orElseThrow().getCurrentPeriodEnd());
        assertEquals(1, mailCount(EmailType.INVOICE));
    }

    @Test
    public void applyWebhookEvent_pending_entersGraceAndWarnsOnce() {
        String id = rzpId();
        seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, id, null, PERIOD, null);

        webhook(id, "subscription.pending");
        webhook(id, "subscription.pending");

        OrgSubscriptionPojo after = subRepo.findByOrgId(orgId).orElseThrow();
        assertEquals(SubscriptionStatus.GRACE, after.getStatus());
        assertNotNull(after.getGraceUntil());
        assertEquals(1, mailCount(EmailType.RENEWAL_WARNING));
    }

    @Test
    public void applyWebhookEvent_halted_withPendingUpgrade_doesNotEnterGrace() {
        String id = rzpId();
        seed(PlanCode.FREE, SubscriptionStatus.ACTIVE, id, PlanCode.STARTER, null, null);

        webhook(id, "subscription.halted");

        OrgSubscriptionPojo after = subRepo.findByOrgId(orgId).orElseThrow();
        assertEquals(SubscriptionStatus.ACTIVE, after.getStatus());
        assertNull(after.getGraceUntil());
        assertEquals(0, mailCount(EmailType.RENEWAL_WARNING));
    }

    @Test
    public void applyWebhookEvent_cancelledOnPaidSubscription_downgradesToFreeAndNotifies() {
        String id = rzpId();
        seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, id, null, PERIOD, null);

        webhook(id, "subscription.cancelled");

        OrgSubscriptionPojo after = subRepo.findByOrgId(orgId).orElseThrow();
        assertEquals(PlanCode.FREE, planSvc.findById(after.getPlanId()).getCode());
        assertNull(after.getRazorpaySubscriptionId());
        assertNull(after.getCurrentPeriodEnd());
        assertEquals(1, mailCount(EmailType.DOWNGRADE_NOTICE));
    }

    @Test
    public void applyWebhookEvent_completed_downgradesToFree() {
        String id = rzpId();
        seed(PlanCode.GROWTH, SubscriptionStatus.ACTIVE, id, null, PERIOD, null);

        webhook(id, "subscription.completed");

        assertEquals(PlanCode.FREE, planSvc.findById(subRepo.findByOrgId(orgId).orElseThrow().getPlanId()).getCode());
    }

    @Test
    public void applyWebhookEvent_unhandledEventType_changesNothing() {
        String id = rzpId();
        seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, id, null, PERIOD, null);

        webhook(id, "payment.authorized");

        OrgSubscriptionPojo after = subRepo.findByOrgId(orgId).orElseThrow();
        assertEquals(PlanCode.STARTER, planSvc.findById(after.getPlanId()).getCode());
        assertEquals(SubscriptionStatus.ACTIVE, after.getStatus());
        assertEquals(0, mailCount(EmailType.INVOICE));
    }

    // ---- reconcile / gateway-status arms ----

    @Test
    public void reconcileOne_missingSubscription_isNoop() {
        subSvc.reconcileOne(-1L);
        assertTrue(gateway.fetched.isEmpty());
    }

    @Test
    public void reconcileOne_subscriptionWithoutGatewayId_isNoop() {
        OrgSubscriptionPojo sub = seed(PlanCode.FREE, SubscriptionStatus.ACTIVE, null, null, null, null);
        subSvc.reconcileOne(sub.getId());
        assertTrue(gateway.fetched.isEmpty());
    }

    @Test
    public void reconcileOne_activeNeverPaid_activatesAndSendsInvoice() {
        String id = rzpId();
        OrgSubscriptionPojo sub = seed(PlanCode.FREE, SubscriptionStatus.PENDING, id, PlanCode.STARTER, null, null);
        gateway.status = new RazorpaySubStatus("active", PERIOD);

        subSvc.reconcileOne(sub.getId());

        assertEquals(List.of(id), gateway.fetched);
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertEquals(PlanCode.STARTER, planSvc.findById(sub.getPlanId()).getCode());
        assertNull(sub.getPendingPlanId());
        assertEquals(PERIOD, sub.getCurrentPeriodEnd());
        assertEquals(1, mailCount(EmailType.INVOICE));
    }

    @Test
    public void reconcileOne_activeSamePeriod_isNoopAndDoesNotReMailInvoice() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("active", PERIOD);

        subSvc.reconcileOne(sub.getId());

        assertEquals(0, mailCount(EmailType.INVOICE));
        assertEquals(PERIOD, sub.getCurrentPeriodEnd());
    }

    @Test
    public void reconcileOne_activeOlderPeriod_isNoop() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("active", PERIOD.minus(1, ChronoUnit.DAYS));

        subSvc.reconcileOne(sub.getId());

        assertEquals(0, mailCount(EmailType.INVOICE));
        assertEquals(PERIOD, sub.getCurrentPeriodEnd());
    }

    @Test
    public void reconcileOne_activeWithNoGatewayPeriodEnd_isNoopWhenPeriodAlreadyKnown() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("active", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(0, mailCount(EmailType.INVOICE));
    }

    @Test
    public void reconcileOne_activeNewerPeriod_advancesPeriodAndMailsInvoice() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        Instant next = PERIOD.plus(30, ChronoUnit.DAYS);
        gateway.status = new RazorpaySubStatus("active", next);

        subSvc.reconcileOne(sub.getId());

        assertEquals(next, sub.getCurrentPeriodEnd());
        assertEquals(1, mailCount(EmailType.INVOICE));
    }

    @Test
    public void reconcileOne_activeWhileInGrace_recoversEvenOnSamePeriod() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.GRACE, rzpId(), null, PERIOD,
            Instant.now().plus(3, ChronoUnit.DAYS));
        gateway.status = new RazorpaySubStatus("active", PERIOD);

        subSvc.reconcileOne(sub.getId());

        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertNull(sub.getGraceUntil());
        assertEquals(1, mailCount(EmailType.INVOICE));
    }

    @Test
    public void reconcileOne_activeInGraceWithNoGatewayPeriodEnd_keepsExistingPeriodEnd() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.GRACE, rzpId(), null, PERIOD,
            Instant.now().plus(3, ChronoUnit.DAYS));
        gateway.status = new RazorpaySubStatus("active", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertEquals(PERIOD, sub.getCurrentPeriodEnd());
    }

    @Test
    public void reconcileOne_activeWithPendingPromotionOnKnownPeriod_promotesPlan() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), PlanCode.GROWTH, PERIOD, null);
        gateway.status = new RazorpaySubStatus("active", PERIOD);

        subSvc.reconcileOne(sub.getId());

        assertEquals(PlanCode.GROWTH, planSvc.findById(sub.getPlanId()).getCode());
        assertNull(sub.getPendingPlanId());
        assertEquals(1, mailCount(EmailType.INVOICE));
    }

    @Test
    public void reconcileOne_pendingStatus_entersGrace() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("pending", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(SubscriptionStatus.GRACE, sub.getStatus());
        assertNotNull(sub.getGraceUntil());
    }

    @Test
    public void reconcileOne_haltedStatus_entersGrace() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("halted", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(SubscriptionStatus.GRACE, sub.getStatus());
    }

    @Test
    public void reconcileOne_cancelledStatus_downgradesToFree() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("cancelled", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(PlanCode.FREE, planSvc.findById(sub.getPlanId()).getCode());
    }

    @Test
    public void reconcileOne_completedStatus_downgradesToFree() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("completed", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(PlanCode.FREE, planSvc.findById(sub.getPlanId()).getCode());
    }

    @Test
    public void reconcileOne_cancelledWithPendingUpgrade_dropsPendingKeepsPlan() {
        OrgSubscriptionPojo sub = seed(PlanCode.FREE, SubscriptionStatus.ACTIVE, rzpId(), PlanCode.STARTER, null, null);
        gateway.status = new RazorpaySubStatus("cancelled", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(PlanCode.FREE, planSvc.findById(sub.getPlanId()).getCode());
        assertNull(sub.getPendingPlanId());
        assertNull(sub.getRazorpaySubscriptionId());
        assertEquals(0, mailCount(EmailType.DOWNGRADE_NOTICE));
    }

    @Test
    public void reconcileOne_unknownGatewayStatus_changesNothing() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        gateway.status = new RazorpaySubStatus("created", null);

        subSvc.reconcileOne(sub.getId());

        assertEquals(PlanCode.STARTER, planSvc.findById(sub.getPlanId()).getCode());
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
    }

    // ---- batch finders / grace expiry ----

    @Test
    public void findReconcilableIds_includesOnlyRowsWithGatewayIdInReconcilableStatus() {
        OrgSubscriptionPojo withId = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);
        Long otherOrg = System.nanoTime();
        OrgSubscriptionPojo noId = seedFor(otherOrg, PlanCode.FREE, SubscriptionStatus.ACTIVE, null, null, null, null);
        Long thirdOrg = System.nanoTime();
        OrgSubscriptionPojo cancelled = seedFor(thirdOrg, PlanCode.STARTER, SubscriptionStatus.CANCELLED, rzpId(), null,
            null, null);

        List<Long> ids = subSvc.findReconcilableIds(1000);

        assertTrue(ids.contains(withId.getId()));
        assertFalse(ids.contains(noId.getId()));
        assertFalse(ids.contains(cancelled.getId()));
    }

    @Test
    public void findGraceExpiredIds_includesOnlyGraceRowsPastTheirDeadline() {
        OrgSubscriptionPojo lapsed = seed(PlanCode.STARTER, SubscriptionStatus.GRACE, rzpId(), null, PERIOD,
            Instant.now().minus(1, ChronoUnit.DAYS));
        OrgSubscriptionPojo stillInGrace = seedFor(System.nanoTime(), PlanCode.STARTER, SubscriptionStatus.GRACE, rzpId(),
            null, PERIOD, Instant.now().plus(3, ChronoUnit.DAYS));
        OrgSubscriptionPojo active = seedFor(System.nanoTime(), PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null,
            PERIOD, null);

        List<Long> ids = subSvc.findGraceExpiredIds(1000);

        assertTrue(ids.contains(lapsed.getId()));
        assertFalse(ids.contains(stillInGrace.getId()));
        assertFalse(ids.contains(active.getId()));
    }

    @Test
    public void expireGraceOne_missingSubscription_isNoop() {
        subSvc.expireGraceOne(-1L);
    }

    @Test
    public void expireGraceOne_recoveredSinceListed_isNoop() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.ACTIVE, rzpId(), null, PERIOD, null);

        subSvc.expireGraceOne(sub.getId());

        assertEquals(PlanCode.STARTER, planSvc.findById(sub.getPlanId()).getCode());
        assertEquals(0, mailCount(EmailType.DOWNGRADE_NOTICE));
    }

    @Test
    public void expireGraceOne_stillInGrace_downgradesToFreeAndNotifies() {
        OrgSubscriptionPojo sub = seed(PlanCode.STARTER, SubscriptionStatus.GRACE, rzpId(), null, PERIOD,
            Instant.now().minus(1, ChronoUnit.DAYS));

        subSvc.expireGraceOne(sub.getId());

        assertEquals(PlanCode.FREE, planSvc.findById(sub.getPlanId()).getCode());
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertEquals(1, mailCount(EmailType.DOWNGRADE_NOTICE));
    }

    /* Private Helper Methods */

    private OrgSubscriptionPojo seed(PlanCode planCode, SubscriptionStatus status, String rzpSubId,
        PlanCode pendingPlanCode, Instant periodEnd, Instant graceUntil) {
        return seedFor(orgId, planCode, status, rzpSubId, pendingPlanCode, periodEnd, graceUntil);
    }

    private OrgSubscriptionPojo seedFor(Long forOrgId, PlanCode planCode, SubscriptionStatus status, String rzpSubId,
        PlanCode pendingPlanCode, Instant periodEnd, Instant graceUntil) {
        Instant now = Instant.now();
        OrgSubscriptionPojo s = new OrgSubscriptionPojo();
        s.setOrgId(forOrgId);
        s.setPlanId(planSvc.findByCode(planCode).getId());
        s.setStatus(status);
        s.setRazorpaySubscriptionId(rzpSubId);
        s.setPendingPlanId(pendingPlanCode == null ? null : planSvc.findByCode(pendingPlanCode).getId());
        s.setCurrentPeriodEnd(periodEnd);
        s.setGraceUntil(graceUntil);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return subRepo.saveAndFlush(s);
    }

    private static String rzpId() {
        return "sub_" + System.nanoTime();
    }

    private void webhook(String rzpSubId, String eventType) {
        subSvc.applyWebhookEvent("evt_" + System.nanoTime(), eventType, rzpSubId, "{}");
    }

    // Outbox rows queued for this test's one ORG_ADMIN, by type.
    private long mailCount(EmailType type) {
        List<EmailOutboxPojo> rows = new ArrayList<>(outboxRepo.findAll());
        return rows.stream() //
            .filter(r -> admin.getId().equals(r.getUserId()) && type == r.getType()) //
            .count();
    }

    /* Test Doubles */

    // Scripted stand-in for the gateway: records cancels/fetches, answers fetches with
    // whatever `status` the test set, and hands out a fixed new subscription + checkout.
    private static class ScriptedGateway extends RazorpayGatewayService {

        static final String NEW_SUB_ID = "sub_new_scripted";
        static final String CHECKOUT_URL = "https://rzp.test/checkout";

        final List<String> cancelled = new ArrayList<>();
        final List<String> fetched = new ArrayList<>();
        RazorpaySubStatus status = new RazorpaySubStatus("active", null);

        @Override
        public RazorpaySubResult createSubscription(String razorpayPlanId) {
            return new RazorpaySubResult(NEW_SUB_ID, CHECKOUT_URL);
        }

        @Override
        public void cancelSubscription(String razorpaySubscriptionId) {
            cancelled.add(razorpaySubscriptionId);
        }

        @Override
        public RazorpaySubStatus fetchSubscription(String razorpaySubscriptionId) {
            fetched.add(razorpaySubscriptionId);
            return status;
        }
    }
}
