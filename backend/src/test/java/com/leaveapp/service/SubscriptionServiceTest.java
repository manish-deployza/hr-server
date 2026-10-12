package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.pojo.OrgSubscriptionPojo;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.repo.UserTestData;

// Covers the money-path business rules -- custom-pricing rejection, seat-cap
// enforcement, and webhook idempotency -- against the FREE/STARTER/ENTERPRISE plans
// PlanService seeds on startup. Each test uses a fresh orgId (System.nanoTime(), not a
// static counter) so org_subscription's unique(org_id) never collides between cases.
public class SubscriptionServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private SubscriptionService subSvc;

    @Autowired
    private PlanService planSvc;

    @Autowired
    private UserRepo userRepo;

    /* Public Methods */

    @Test
    public void createFreeSubscription_startsActiveOnFree() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo sub = subSvc.getCurrent(orgId);
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertNull(sub.getRazorpaySubscriptionId());
        assertEquals(PlanCode.FREE, planSvc.findById(sub.getPlanId()).getCode());
    }

    @Test
    public void getCurrent_missing_throws() {
        assertThrows(NotFoundException.class, () -> subSvc.getCurrent(freshOrgId()));
    }

    @Test
    public void assertSeatAvailable_underCap_succeeds() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId); // FREE seatLimit=5
        userRepo.save(UserTestData.aUser(orgId, "a@test.com"));
        subSvc.assertSeatAvailable(orgId); // 1 < 5 -- must not throw
    }

    @Test
    public void assertSeatAvailable_atCap_throws() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId); // FREE seatLimit=5
        for (int i = 0; i < 5; i++) {
            userRepo.save(UserTestData.aUser(orgId, "u" + i + "@test.com"));
        }
        assertThrows(ConflictException.class, () -> subSvc.assertSeatAvailable(orgId));
    }

    @Test
    public void remainingSeats_computesGap() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        userRepo.save(UserTestData.aUser(orgId, "b@test.com"));
        assertEquals(4, subSvc.remainingSeats(orgId));
    }


    @Test
    public void subscribe_customPricingPlan_throws() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        assertThrows(ConflictException.class, () -> subSvc.subscribe(orgId, PlanCode.ENTERPRISE).sub());
    }

    @Test
    public void subscribe_samePlanAlreadyActive_isNoop() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo result = subSvc.subscribe(orgId, PlanCode.FREE).sub();
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertNull(result.getRazorpaySubscriptionId());
    }

    @Test
    public void subscribe_toPaidPlan_keepsCurrentPlanUntilPaid() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        SubscribeResult res = subSvc.subscribe(orgId, PlanCode.STARTER);
        OrgSubscriptionPojo result = res.sub();
        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertEquals(PlanCode.FREE, planSvc.findById(result.getPlanId()).getCode());
        assertEquals(PlanCode.STARTER, planSvc.findById(result.getPendingPlanId()).getCode());
        assertNotNull(result.getRazorpaySubscriptionId());
        // disabled-stub RazorpayGatewayService.createSubscription returns no real
        // checkout URL (there's no real gateway to redirect to) -- null is correct here.
        assertNull(res.checkoutUrl());
    }

    @Test
    public void insertEvent_firstInsert_succeeds() {
        subSvc.insertEvent("evt_new_1", "subscription.charged", 1L, "{}");
    }

    @Test
    public void insertEvent_duplicate_throws() {
        subSvc.insertEvent("evt_new_2", "subscription.charged", 1L, "{}");
        assertThrows(DataIntegrityViolationException.class,
            () -> subSvc.insertEvent("evt_new_2", "subscription.charged", 1L, "{}"));
    }

    @Test
    public void applyWebhookEvent_activatesPendingSubscription() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo pending = subSvc.subscribe(orgId, PlanCode.STARTER).sub();
        subSvc.applyWebhookEvent("evt_activate_" + orgId, "subscription.activated",
            pending.getRazorpaySubscriptionId(), "{}");
        OrgSubscriptionPojo after = subSvc.getCurrent(orgId);
        assertEquals(SubscriptionStatus.ACTIVE, after.getStatus());
        assertNotNull(after.getCurrentPeriodEnd());
        assertEquals(PlanCode.STARTER, planSvc.findById(after.getPlanId()).getCode());
        assertNull(after.getPendingPlanId());
    }

    @Test
    public void applyWebhookEvent_cancelledBeforePayment_dropsPendingKeepsPlan() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo pending = subSvc.subscribe(orgId, PlanCode.STARTER).sub();
        subSvc.applyWebhookEvent("evt_abandon_" + orgId, "subscription.cancelled",
            pending.getRazorpaySubscriptionId(), "{}");
        OrgSubscriptionPojo after = subSvc.getCurrent(orgId);
        assertEquals(PlanCode.FREE, planSvc.findById(after.getPlanId()).getCode());
        assertNull(after.getPendingPlanId());
    }

    @Test
    public void applyWebhookEvent_duplicateEventId_secondDeliveryIsNoop() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo pending = subSvc.subscribe(orgId, PlanCode.STARTER).sub();
        String rzpId = pending.getRazorpaySubscriptionId();
        String eventId = "evt_dup_" + orgId;
        subSvc.applyWebhookEvent(eventId, "subscription.activated", rzpId, "{}");
        subSvc.applyWebhookEvent(eventId, "subscription.activated", rzpId, "{}"); // must not throw or re-apply
        assertEquals(SubscriptionStatus.ACTIVE, subSvc.getCurrent(orgId).getStatus());
    }

    @Test
    public void applyWebhookEvent_unknownRazorpaySubId_isNoop() {
        // No matching org_subscription row -- must log and return, never throw.
        subSvc.applyWebhookEvent("evt_unknown", "subscription.activated", "sub_does_not_exist", "{}");
    }

    @Test
    public void graceThenExpiry_downgradesToFree() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo pending = subSvc.subscribe(orgId, PlanCode.STARTER).sub();
        subSvc.applyWebhookEvent("evt_paid_" + orgId, "subscription.activated",
            pending.getRazorpaySubscriptionId(), "{}");
        subSvc.applyWebhookEvent("evt_grace_" + orgId, "subscription.halted",
            pending.getRazorpaySubscriptionId(), "{}");
        OrgSubscriptionPojo inGrace = subSvc.getCurrent(orgId);
        assertEquals(SubscriptionStatus.GRACE, inGrace.getStatus());
        assertNotNull(inGrace.getGraceUntil());

        subSvc.expireGraceOne(inGrace.getId());

        OrgSubscriptionPojo after = subSvc.getCurrent(orgId);
        assertEquals(SubscriptionStatus.ACTIVE, after.getStatus());
        assertEquals(PlanCode.FREE, planSvc.findById(after.getPlanId()).getCode());
        assertNull(after.getRazorpaySubscriptionId());
    }

    @Test
    public void grace_repeatedSighting_doesNotPushGraceUntilOut() {
        Long orgId = freshOrgId();
        subSvc.createFreeSubscription(orgId);
        OrgSubscriptionPojo pending = subSvc.subscribe(orgId, PlanCode.STARTER).sub();
        String rzpId = pending.getRazorpaySubscriptionId();
        subSvc.applyWebhookEvent("evt_grace1_" + orgId, "subscription.halted", rzpId, "{}");
        java.time.Instant first = subSvc.getCurrent(orgId).getGraceUntil();

        subSvc.applyWebhookEvent("evt_grace2_" + orgId, "subscription.pending", rzpId, "{}");
        java.time.Instant second = subSvc.getCurrent(orgId).getGraceUntil();

        assertEquals(first, second);
    }

    /* Private Helper Methods */

    private static Long freshOrgId() {
        return System.nanoTime();
    }
}
