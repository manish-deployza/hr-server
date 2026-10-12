package com.leaveapp.model.enums;

// org_subscription's own state machine -- see prompts/features/billing-subscription.md.
public enum SubscriptionStatus {
    // Razorpay subscription created, awaiting the admin's checkout authorization.
    PENDING,
    ACTIVE,
    // payment failing/retrying; org keeps access until grace_until.
    GRACE,
    // terminal at the gateway; the same transaction downgrades to FREE/ACTIVE right after.
    CANCELLED
}
