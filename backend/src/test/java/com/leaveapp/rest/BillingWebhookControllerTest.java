package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.service.RazorpayGatewayService;
import com.leaveapp.service.SubscriptionService;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

// Plain unit test: the public signature-authenticated webhook. Covers the signature gate
// (nothing parsed or applied on a bad signature), the ack-and-drop paths (bad JSON, missing
// event / subscription id), every event-id source (header, blank header, body field,
// SHA-256 fallback), and a failing apply being swallowed so Razorpay never retry-storms.
public class BillingWebhookControllerTest {

    /* Variables */

    private static final String BODY =
        "{\"event\":\"subscription.charged\",\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}}";
    private static final String BODY_WITH_EVENT_ID =
        "{\"event_id\":\"evt_body\",\"event\":\"subscription.charged\","
            + "\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}}";

    private final RecordingGateway gateway = new RecordingGateway();
    private final RecordingSubSvc subSvc = new RecordingSubSvc();
    private final BillingWebhookController controller = new BillingWebhookController();

    {
        ReflectionTestUtils.setField(controller, "razorpaySvc", gateway);
        ReflectionTestUtils.setField(controller, "subscriptionSvc", subSvc);
    }

    /* Public Methods */

    @Test
    public void razorpay_invalidSignature_badRequestAndNothingApplied() {
        gateway.valid = false;
        assertThrows(BadRequestException.class, () -> controller.razorpay(request(BODY), "bad"));
        assertEquals(BODY, gateway.lastBody);
        assertEquals("bad", gateway.lastSignature);
        assertTrue(subSvc.calls.isEmpty());
    }

    @Test
    public void razorpay_missingSignature_passedAsNullToGateway() {
        gateway.valid = false;
        assertThrows(BadRequestException.class, () -> controller.razorpay(request(BODY), null));
        assertNull(gateway.lastSignature);
    }

    @Test
    public void razorpay_notJson_ackedWithoutApplying() throws IOException {
        assertNotNull(controller.razorpay(request("{not json"), "sig"));
        assertTrue(subSvc.calls.isEmpty());
    }

    @Test
    public void razorpay_missingEventType_ackedWithoutApplying() throws IOException {
        controller.razorpay(request("{\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}}"), "sig");
        assertTrue(subSvc.calls.isEmpty());
    }

    @Test
    public void razorpay_missingSubscriptionId_ackedWithoutApplying() throws IOException {
        controller.razorpay(request("{\"event\":\"subscription.charged\"}"), "sig");
        assertTrue(subSvc.calls.isEmpty());
    }

    @Test
    public void razorpay_eventIdFromHeader_wins() throws IOException {
        MockHttpServletRequest req = request(BODY_WITH_EVENT_ID);
        req.addHeader("X-Razorpay-Event-Id", "evt_header");

        assertNotNull(controller.razorpay(req, "sig"));

        assertEquals(1, subSvc.calls.size());
        assertTrue(subSvc.calls.get(0).startsWith("evt_header|subscription.charged|sub_1|"), subSvc.calls.get(0));
        assertTrue(subSvc.calls.get(0).endsWith(BODY_WITH_EVENT_ID), "raw payload passed through");
    }

    @Test
    public void razorpay_blankHeader_fallsBackToBodyEventId() throws IOException {
        MockHttpServletRequest req = request(BODY_WITH_EVENT_ID);
        req.addHeader("X-Razorpay-Event-Id", "   ");

        controller.razorpay(req, "sig");

        assertTrue(subSvc.calls.get(0).startsWith("evt_body|"), subSvc.calls.get(0));
    }

    @Test
    public void razorpay_noHeaderNoBodyId_usesDeterministicSha256OfRawBody() throws IOException {
        controller.razorpay(request(BODY), "sig");
        controller.razorpay(request(BODY), "sig");
        controller.razorpay(request(BODY + " "), "sig");

        String first = subSvc.calls.get(0).split("\\|")[0];
        assertTrue(first.matches("[0-9a-f]{64}"), first);
        assertEquals(first, subSvc.calls.get(1).split("\\|")[0]);
        assertNotEquals(first, subSvc.calls.get(2).split("\\|")[0]);
    }

    @Test
    public void razorpay_applyFails_stillAcked() throws IOException {
        subSvc.fail = true;
        assertNotNull(controller.razorpay(request(BODY), "sig"));
        assertEquals(1, subSvc.calls.size());
    }

    @Test
    public void razorpay_unreadableBody_ioExceptionPropagates() {
        HttpServletRequest broken = new HttpServletRequestWrapper(new MockHttpServletRequest()) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                throw new IOException("stream closed");
            }
        };
        assertThrows(IOException.class, () -> controller.razorpay(broken, "sig"));
    }

    /* Private Helper Methods */

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /* Test Doubles */

    private static final class RecordingGateway extends RazorpayGatewayService {
        private boolean valid = true;
        private String lastBody;
        private String lastSignature;

        @Override
        public boolean verifySignature(byte[] rawBody, String signature) {
            lastBody = new String(rawBody, StandardCharsets.UTF_8);
            lastSignature = signature;
            return valid;
        }
    }

    private static final class RecordingSubSvc extends SubscriptionService {
        private final List<String> calls = new ArrayList<>();
        private boolean fail;

        @Override
        public void applyWebhookEvent(String eventId, String eventType, String razorpaySubId, String rawPayload) {
            calls.add(eventId + "|" + eventType + "|" + razorpaySubId + "|" + rawPayload);
            if (fail) {
                throw new IllegalStateException("boom");
            }
        }
    }
}
