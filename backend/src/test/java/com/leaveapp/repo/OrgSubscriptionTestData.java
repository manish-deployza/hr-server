package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.pojo.OrgSubscriptionPojo;

public class OrgSubscriptionTestData {

    /* Public Methods */

    public static OrgSubscriptionPojo aSubscription(Long orgId, Long planId) {
        Instant now = Instant.now();
        OrgSubscriptionPojo s = new OrgSubscriptionPojo();
        s.setOrgId(orgId);
        s.setPlanId(planId);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return s;
    }
}
