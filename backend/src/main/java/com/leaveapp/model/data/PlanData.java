package com.leaveapp.model.data;

import com.leaveapp.model.enums.BillingPeriod;
import com.leaveapp.model.enums.PlanCode;

import lombok.Getter;
import lombok.Setter;

// GET /api/billing/plans row. priceMinor/currency read as "Contact us" in the UI
// whenever isCustomPricing is true (ENTERPRISE) -- the number isn't meaningful there.
@Getter
@Setter
public class PlanData {

    /* Variables */

    private PlanCode code;
    private String name;
    private int seatLimit;
    private long priceMinor;
    private String currency;
    private BillingPeriod billingPeriod;
    private boolean customPricing;
}
