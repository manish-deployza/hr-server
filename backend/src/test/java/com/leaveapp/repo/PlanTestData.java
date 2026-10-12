package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.BillingPeriod;
import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.pojo.PlanPojo;

public class PlanTestData {

    /* Public Methods */

    public static PlanPojo aPlan(PlanCode code) {
        PlanPojo p = new PlanPojo();
        p.setCode(code);
        p.setName("Test " + code);
        p.setSeatLimit(10);
        p.setPriceMinor(1000);
        p.setCurrency("INR");
        p.setBillingPeriod(BillingPeriod.MONTHLY);
        p.setActive(true);
        p.setCustomPricing(false);
        p.setCreatedAt(Instant.now());
        return p;
    }
}
