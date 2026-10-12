package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.PlanData;
import com.leaveapp.model.data.SubscriptionData;
import com.leaveapp.model.form.SubscribeForm;
import com.leaveapp.workflow.BillingFlow;

// Plan catalog + the caller's own org subscription. ORG_ADMIN only, matching
// OrgController's gate -- the plan's "not yet confirmed" question about letting HR view
// (not change) plan/usage defaulted to ORG_ADMIN-only for this pass.
@RestController
@RequestMapping("/api/billing")
@PreAuthorize("hasRole('ORG_ADMIN')")
public class BillingController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    @Autowired
    private BillingFlow billingFlow;

    /* Public Methods */

    @GetMapping("/plans")
    public List<PlanData> plans() {
        log.info("GET /api/billing/plans orgId={}", requireOrgId());
        return billingFlow.plans();
    }

    @GetMapping("/subscription")
    public SubscriptionData subscription() {
        Long orgId = requireOrgId();
        log.info("GET /api/billing/subscription orgId={}", orgId);
        return billingFlow.current(orgId);
    }

    @PostMapping("/subscribe")
    public SubscriptionData subscribe(@RequestBody SubscribeForm form) {
        Long orgId = requireOrgId();
        log.info("POST /api/billing/subscribe orgId={} planCode={}", orgId, form.getPlanCode());
        return billingFlow.subscribe(orgId, form);
    }

    @PostMapping("/cancel")
    public SubscriptionData cancel() {
        Long orgId = requireOrgId();
        log.info("POST /api/billing/cancel orgId={}", orgId);
        return billingFlow.cancel(orgId);
    }
}
