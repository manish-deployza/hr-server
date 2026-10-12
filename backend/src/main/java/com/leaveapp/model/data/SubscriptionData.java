package com.leaveapp.model.data;

import java.time.Instant;

import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.model.enums.SubscriptionStatus;

import lombok.Getter;
import lombok.Setter;

// GET /api/billing/subscription and POST /api/billing/subscribe response.
// checkoutUrl is set only when a just-created paid subscription still needs the
// admin to authorize payment; null on every plain GET.
@Getter
@Setter
public class SubscriptionData {

    /* Variables */

    private PlanCode planCode;
    private String planName;
    private int seatLimit;
    private int seatsUsed;
    private SubscriptionStatus status;
    // plan awaiting payment confirmation, null when none; planCode is still the plan in force
    private PlanCode pendingPlanCode;
    private Instant currentPeriodEnd;
    private Instant graceUntil;
    private String checkoutUrl;
    // Both set only alongside checkoutUrl: what Razorpay's Checkout.js popup needs
    // (key = public key id, subscription_id). Null otherwise.
    private String razorpaySubscriptionId;
    private String razorpayKeyId;
}
