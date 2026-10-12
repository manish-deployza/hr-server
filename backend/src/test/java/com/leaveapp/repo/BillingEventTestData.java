package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.pojo.BillingEventPojo;

public class BillingEventTestData {

    /* Public Methods */

    public static BillingEventPojo anEvent(String eventId) {
        Instant now = Instant.now();
        BillingEventPojo e = new BillingEventPojo();
        e.setRazorpayEventId(eventId);
        e.setEventType("subscription.charged");
        e.setOrgId(1L);
        e.setPayload("{}");
        e.setProcessedAt(now);
        e.setCreatedAt(now);
        return e;
    }
}
