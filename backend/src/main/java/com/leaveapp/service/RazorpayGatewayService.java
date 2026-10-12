package com.leaveapp.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Subscription;
import com.razorpay.Utils;

// The one class that is allowed to know Razorpay exists -- gated by razorpay.enabled
// (default false, same pattern as CaptchaService/MailService). No caller outside this
// class ever sees a Razorpay SDK type or a raw HTTP call.
@Service
public class RazorpayGatewayService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(RazorpayGatewayService.class);
    // ponytail: 120 monthly cycles (10 years); Razorpay requires a finite total_count.
    private static final int TOTAL_COUNT = 120;

    private RazorpayClient client;

    @Value("${razorpay.enabled}")
    private boolean enabled;

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    /* Public Methods */

    public RazorpaySubResult createSubscription(String razorpayPlanId) {
        if (!enabled) {
            String subId = "local_sub_" + UUID.randomUUID();
            log.info("razorpay disabled -- synthetic subscription created id={}", subId);
            return new RazorpaySubResult(subId, null);
        }
        try {
            Subscription sub = client().subscriptions.create(new JSONObject()
                    .put("plan_id", razorpayPlanId)
                    .put("total_count", TOTAL_COUNT)
                    .put("customer_notify", 1));
            return new RazorpaySubResult(sub.get("id"), sub.get("short_url"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("razorpay create subscription failed", e);
        }
    }

    public void cancelSubscription(String razorpaySubscriptionId) {
        if (!enabled) {
            log.info("razorpay disabled -- synthetic subscription cancel id={}", razorpaySubscriptionId);
            return;
        }
        try {
            client().subscriptions.cancel(razorpaySubscriptionId);
        } catch (RazorpayException e) {
            throw new IllegalStateException("razorpay cancel subscription failed id=" + razorpaySubscriptionId, e);
        }
    }

    // Used by both the webhook path (activated/charged) and SubscriptionScheduler's
    // reconcile poll -- the disabled stub reports "active" so dev/test can exercise the
    // whole purchase flow with no credentials or network.
    public RazorpaySubStatus fetchSubscription(String razorpaySubscriptionId) {
        if (!enabled) {
            return new RazorpaySubStatus("active", Instant.now().plus(30, ChronoUnit.DAYS));
        }
        try {
            JSONObject sub = client().subscriptions.fetch(razorpaySubscriptionId).toJson();
            long end = sub.optLong("current_end", 0); // null until the first charge
            return new RazorpaySubStatus(sub.getString("status"), end > 0 ? Instant.ofEpochSecond(end) : null);
        } catch (RazorpayException e) {
            throw new IllegalStateException("razorpay fetch subscription failed id=" + razorpaySubscriptionId, e);
        }
    }

    // BillingWebhookController verifies the raw body's signature before parsing it --
    // disabled mode accepts unconditionally so dev/test never needs a real
    // razorpay.webhook-secret to exercise the webhook endpoint.
    public boolean verifySignature(byte[] rawBody, String signature) {
        if (!enabled) {
            return true;
        }
        if (signature == null) {
            return false;
        }
        try {
            return Utils.verifyWebhookSignature(new String(rawBody, StandardCharsets.UTF_8), signature, webhookSecret);
        } catch (RazorpayException e) {
            log.warn("razorpay webhook signature check failed: {}", e.getMessage());
            return false;
        }
    }

    /* Private Methods */

    private synchronized RazorpayClient client() throws RazorpayException {
        if (client == null) {
            client = new RazorpayClient(keyId, keySecret);
        }
        return client;
    }
}
