package com.leaveapp.service;

import com.leaveapp.pojo.OrgSubscriptionPojo;

// SubscriptionService.subscribe's return shape: the updated row plus the checkout URL
// the admin must open, which exists only for this one call (null unless a new Razorpay
// subscription was created). Same internal Service<->Flow carrier as RazorpaySubResult.
public record SubscribeResult(OrgSubscriptionPojo sub, String checkoutUrl) {
}
