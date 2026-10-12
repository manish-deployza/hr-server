package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// BillingFlow -> PlanService         -> PlanRepo             -> DB (plan catalog)
// BillingFlow -> SubscriptionService -> OrgSubscriptionRepo   -> DB (the org's subscription)
// BillingFlow -> UserService         -> UserRepo              -> DB (seatsUsed)
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.PlanData;
import com.leaveapp.model.data.SubscriptionData;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.model.form.SubscribeForm;
import com.leaveapp.pojo.OrgSubscriptionPojo;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.service.PlanService;
import com.leaveapp.service.SubscribeResult;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// Thin orchestration only, per layered-flow.md -- resolves nothing of its own (orgId
// is already the JWT claim, resolved by BillingController), calls PlanService /
// SubscriptionService, maps *Pojo -> *Data. Holds no business rules; those live in
// SubscriptionService (see that class's header comment for why).
@Service
public class BillingFlow {

    /* Variables */

    @Autowired
    private PlanService planSvc;

    @Autowired
    private SubscriptionService subSvc;

    @Autowired
    private UserService userSvc;

    @Value("${razorpay.key-id}")
    private String razorpayKeyId;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<PlanData> plans() {
        return planSvc.listActive().stream().map(BillingFlow::toPlanData).toList();
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SubscriptionData current(Long orgId) {
        return toData(orgId, subSvc.getCurrent(orgId));
    }

    @Transactional(rollbackFor = Exception.class)
    public SubscriptionData subscribe(Long orgId, SubscribeForm form) {
        if (form.getPlanCode() == null || form.getPlanCode().isBlank()) {
            throw new BadRequestException("planCode is required");
        }
        return toData(orgId, subSvc.subscribe(orgId, parseCode(form.getPlanCode())));
    }

    // Cancelling is just switching to FREE -- SubscriptionService.subscribe already
    // cancels any existing Razorpay subscription and downgrades the local row when the
    // target plan is free, so no new orchestration is needed here.
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionData cancel(Long orgId) {
        return toData(orgId, subSvc.subscribe(orgId, PlanCode.FREE));
    }

    /* Private Helper Methods */

    private static PlanCode parseCode(String code) {
        try {
            return PlanCode.valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("unknown plan code");
        }
    }

    private SubscriptionData toData(Long orgId, SubscribeResult result) {
        SubscriptionData data = toData(orgId, result.sub());
        data.setCheckoutUrl(result.checkoutUrl());
        if (result.checkoutUrl() != null) {
            data.setRazorpaySubscriptionId(result.sub().getRazorpaySubscriptionId());
            data.setRazorpayKeyId(razorpayKeyId);
        }
        return data;
    }

    private SubscriptionData toData(Long orgId, OrgSubscriptionPojo sub) {
        PlanPojo plan = planSvc.findById(sub.getPlanId());
        SubscriptionData data = new SubscriptionData();
        data.setPlanCode(plan.getCode());
        data.setPlanName(plan.getName());
        data.setSeatLimit(plan.getSeatLimit());
        data.setSeatsUsed((int) userSvc.countActiveByOrgId(orgId));
        data.setStatus(sub.getStatus());
        if (sub.getPendingPlanId() != null) {
            data.setPendingPlanCode(planSvc.findById(sub.getPendingPlanId()).getCode());
        }
        data.setCurrentPeriodEnd(sub.getCurrentPeriodEnd());
        data.setGraceUntil(sub.getGraceUntil());
        return data;
    }

    private static PlanData toPlanData(PlanPojo p) {
        PlanData d = new PlanData();
        d.setCode(p.getCode());
        d.setName(p.getName());
        d.setSeatLimit(p.getSeatLimit());
        d.setPriceMinor(p.getPriceMinor());
        d.setCurrency(p.getCurrency());
        d.setBillingPeriod(p.getBillingPeriod());
        d.setCustomPricing(p.isCustomPricing());
        return d;
    }
}
