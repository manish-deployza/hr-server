package com.leaveapp.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.AckData;
import com.leaveapp.service.RazorpayGatewayService;
import com.leaveapp.service.SubscriptionService;

import jakarta.servlet.http.HttpServletRequest;

// Public, signature-authenticated (SecurityConfig permitAll) -- see
// prompts/features/billing-subscription.md, Edge Cases ("Webhook signature") and
// SubscriptionService.applyWebhookEvent's own header comment ("Called from
// BillingWebhookController once the signature has already verified").
//
// Reads the request body as raw bytes off HttpServletRequest rather than
// @RequestBody byte[]: WebConfig registers only MappingJackson2HttpMessageConverter,
// which has no byte[] support, so a byte[] parameter would fail before this method
// ever ran. The HMAC signature covers those exact raw bytes, so nothing may touch the
// stream before verifySignature runs.
@RestController
@RequestMapping("/api/billing/webhook")
public class BillingWebhookController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RazorpayGatewayService razorpaySvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    @PostMapping("/razorpay")
    public AckData razorpay(HttpServletRequest req,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) throws IOException {
        byte[] rawBody = req.getInputStream().readAllBytes();
        if (!razorpaySvc.verifySignature(rawBody, signature)) {
            log.warn("razorpay webhook signature invalid or missing");
            throw new BadRequestException("invalid signature");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            // Nothing a retry can fix -- ack so Razorpay stops resending the same bytes.
            log.warn("razorpay webhook payload not valid JSON (signature was valid)");
            return new AckData();
        }

        String eventType = root.path("event").asText(null);
        String subscriptionId = root.path("payload").path("subscription").path("entity").path("id").asText(null);
        String eventId = eventId(req, root, rawBody);

        if (eventType == null || subscriptionId == null) {
            log.warn("razorpay webhook missing event/subscription id eventId={}", eventId);
            return new AckData();
        }

        try {
            String rawPayload = new String(rawBody, StandardCharsets.UTF_8);
            subscriptionSvc.applyWebhookEvent(eventId, eventType, subscriptionId, rawPayload);
        } catch (Exception e) {
            // Never let one bad event turn into an endless Razorpay retry storm --
            // SubscriptionScheduler.reconcile is the actual catch-all for a transition
            // that failed to apply, not Razorpay's own webhook retry (see the feature
            // doc's RazorpayGatewayService edge case: one org's gateway error must not
            // crash the webhook handler).
            log.error("razorpay webhook processing failed eventId={} eventType={}", eventId, eventType, e);
        }
        return new AckData();
    }

    /* Private Helper Methods */

    // Prefers Razorpay's own event id (header, then a body field) so the idempotency
    // dedup in SubscriptionService is keyed on the gateway's real identifier; a
    // SHA-256 hash of the raw body is the last-resort fallback. Unverified against a
    // real Razorpay payload -- see the feature doc's Implementation Notes.
    private String eventId(HttpServletRequest req, JsonNode root, byte[] rawBody) {
        String header = req.getHeader("X-Razorpay-Event-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String fromBody = root.path("event_id").asText(null);
        if (fromBody != null) {
            return fromBody;
        }
        return digestHex("SHA-256", rawBody);
    }

    // Package-private so a test can pass an unknown algorithm to reach the catch.
    String digestHex(String algorithm, byte[] rawBody) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(rawBody);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }
}
