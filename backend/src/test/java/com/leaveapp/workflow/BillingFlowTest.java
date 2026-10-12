package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.PlanData;
import com.leaveapp.model.data.SubscriptionData;
import com.leaveapp.model.enums.BillingPeriod;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.model.form.SubscribeForm;
import com.leaveapp.pojo.OrgSubscriptionPojo;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.service.PlanService;
import com.leaveapp.service.SubscribeResult;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// Plain unit test: BillingFlow holds no rules of its own (those are SubscriptionService's
// and covered there), so the three collaborators are hand-written doubles and the cases
// are the form validation and the *Pojo -> *Data mapping branches.
public class BillingFlowTest {

    /* Variables */

    private final StubPlanService planSvc = new StubPlanService();
    private final StubSubscriptionService subSvc = new StubSubscriptionService();
    private final BillingFlow flow = new BillingFlow();

    {
        ReflectionTestUtils.setField(flow, "planSvc", planSvc);
        ReflectionTestUtils.setField(flow, "subSvc", subSvc);
        ReflectionTestUtils.setField(flow, "userSvc", new StubUserService());
        ReflectionTestUtils.setField(flow, "razorpayKeyId", "rzp_key");
        planSvc.plans.put(1L, plan(1L, PlanCode.FREE, "Free", 5, 0, false));
        planSvc.plans.put(2L, plan(2L, PlanCode.STARTER, "Starter", 25, 99_900, false));
    }

    /* Public Methods */

    @Test
    public void plans_mapsEveryActivePlanToPlanData() {
        planSvc.plans.put(3L, plan(3L, PlanCode.ENTERPRISE, "Enterprise", Integer.MAX_VALUE, 0, true));

        List<PlanData> data = flow.plans();

        assertEquals(3, data.size());
        PlanData starter = data.stream().filter(d -> PlanCode.STARTER == d.getCode()).findFirst().orElseThrow();
        assertEquals("Starter", starter.getName());
        assertEquals(25, starter.getSeatLimit());
        assertEquals(99_900, starter.getPriceMinor());
        assertEquals("INR", starter.getCurrency());
        assertEquals(BillingPeriod.MONTHLY, starter.getBillingPeriod());
        assertFalse(starter.isCustomPricing());
        assertTrue(data.stream().filter(d -> PlanCode.ENTERPRISE == d.getCode()).findFirst().orElseThrow()
            .isCustomPricing());
    }

    @Test
    public void plans_noActivePlans_returnsEmptyList() {
        planSvc.plans.clear();
        assertTrue(flow.plans().isEmpty());
    }

    @Test
    public void current_plainActiveSubscription_omitsPendingAndCheckoutFields() {
        subSvc.current = sub(1L, null);

        SubscriptionData data = flow.current(7L);

        assertEquals(PlanCode.FREE, data.getPlanCode());
        assertEquals("Free", data.getPlanName());
        assertEquals(5, data.getSeatLimit());
        assertEquals(3, data.getSeatsUsed());
        assertEquals(SubscriptionStatus.ACTIVE, data.getStatus());
        assertNull(data.getPendingPlanCode());
        assertNull(data.getCheckoutUrl());
        assertNull(data.getRazorpaySubscriptionId());
        assertNull(data.getRazorpayKeyId());
        assertEquals(subSvc.current.getCurrentPeriodEnd(), data.getCurrentPeriodEnd());
        assertEquals(subSvc.current.getGraceUntil(), data.getGraceUntil());
    }

    @Test
    public void subscribe_withCheckout_includesPendingPlanAndCheckoutHandshake() {
        subSvc.current = sub(1L, 2L);
        subSvc.checkoutUrl = "https://rzp.io/checkout";

        SubscriptionData data = flow.subscribe(7L, form("STARTER"));

        assertEquals(PlanCode.STARTER, data.getPendingPlanCode());
        assertEquals("https://rzp.io/checkout", data.getCheckoutUrl());
        assertEquals("sub_rzp", data.getRazorpaySubscriptionId());
        assertEquals("rzp_key", data.getRazorpayKeyId());
    }

    @Test
    public void current_pendingUpgrade_omitsCheckoutHandshake() {
        subSvc.current = sub(1L, 2L);

        SubscriptionData data = flow.current(7L);

        assertEquals(PlanCode.STARTER, data.getPendingPlanCode());
        assertNull(data.getRazorpaySubscriptionId());
        assertNull(data.getRazorpayKeyId());
    }

    @Test
    public void subscribe_nullPlanCode_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> flow.subscribe(7L, form(null)));
        assertNull(subSvc.subscribedCode);
    }

    @Test
    public void subscribe_blankPlanCode_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> flow.subscribe(7L, form("   ")));
        assertNull(subSvc.subscribedCode);
    }

    @Test
    public void subscribe_unknownPlanCode_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> flow.subscribe(7L, form("NOPE")));
        assertNull(subSvc.subscribedCode);
    }

    @Test
    public void subscribe_validPlanCode_trimsAndDelegatesForOrg() {
        subSvc.current = sub(1L, 2L);

        SubscriptionData data = flow.subscribe(7L, form("  STARTER "));

        assertEquals(PlanCode.STARTER, subSvc.subscribedCode);
        assertEquals(7L, subSvc.subscribedOrgId);
        assertEquals(PlanCode.STARTER, data.getPendingPlanCode());
    }

    @Test
    public void cancel_subscribesToFreePlan() {
        subSvc.current = sub(1L, null);

        SubscriptionData data = flow.cancel(7L);

        assertEquals(PlanCode.FREE, subSvc.subscribedCode);
        assertEquals(7L, subSvc.subscribedOrgId);
        assertEquals(PlanCode.FREE, data.getPlanCode());
    }

    /* Private Helper Methods */

    private static SubscribeForm form(String planCode) {
        SubscribeForm f = new SubscribeForm();
        f.setPlanCode(planCode);
        return f;
    }

    private static PlanPojo plan(Long id, PlanCode code, String name, int seats, long price, boolean custom) {
        PlanPojo p = new PlanPojo();
        p.setId(id);
        p.setCode(code);
        p.setName(name);
        p.setSeatLimit(seats);
        p.setPriceMinor(price);
        p.setCurrency("INR");
        p.setBillingPeriod(BillingPeriod.MONTHLY);
        p.setCustomPricing(custom);
        return p;
    }

    private static OrgSubscriptionPojo sub(Long planId, Long pendingPlanId) {
        OrgSubscriptionPojo s = new OrgSubscriptionPojo();
        s.setPlanId(planId);
        s.setPendingPlanId(pendingPlanId);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setRazorpaySubscriptionId("sub_rzp");
        s.setCurrentPeriodEnd(Instant.parse("2030-01-01T00:00:00Z"));
        s.setGraceUntil(Instant.parse("2030-01-08T00:00:00Z"));
        return s;
    }

    /* Test Doubles */

    private static class StubPlanService extends PlanService {

        final Map<Long, PlanPojo> plans = new HashMap<>();

        @Override
        public List<PlanPojo> listActive() {
            return new ArrayList<>(plans.values());
        }

        @Override
        public PlanPojo findById(Long id) {
            return plans.get(id);
        }
    }

    private static class StubSubscriptionService extends SubscriptionService {

        OrgSubscriptionPojo current;
        Long subscribedOrgId;
        PlanCode subscribedCode;
        String checkoutUrl;

        @Override
        public OrgSubscriptionPojo getCurrent(Long orgId) {
            return current;
        }

        @Override
        public SubscribeResult subscribe(Long orgId, PlanCode planCode) {
            subscribedOrgId = orgId;
            subscribedCode = planCode;
            return new SubscribeResult(current, checkoutUrl);
        }
    }

    private static class StubUserService extends UserService {

        @Override
        public long countActiveByOrgId(Long orgId) {
            return 3;
        }
    }
}
