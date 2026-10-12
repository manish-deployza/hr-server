package com.leaveapp.service;

// RazorpayGatewayService.createSubscription's return shape: the gateway's own
// subscription id (to store) plus the checkout URL the admin must open to authorize
// payment. Not a *Pojo/*Form/*Data -- an internal Service<->Service carrier, same
// spirit as SessionData's onboarding fields but scoped to one call.
public record RazorpaySubResult(String subscriptionId, String checkoutUrl) {
}
