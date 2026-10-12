package com.leaveapp.service;

import java.time.Instant;

// RazorpayGatewayService.fetchSubscription's return shape: the gateway's own current
// status string ("active"/"pending"/"halted"/"cancelled"/"completed", per Razorpay's
// Subscription entity) plus its current_end, used by both the webhook path and
// SubscriptionService.reconcileOne to apply the same transition logic.
public record RazorpaySubStatus(String status, Instant currentPeriodEnd) {
}
