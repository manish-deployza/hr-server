package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.razorpay.FakeSubscriptionClient;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Subscription;
import com.razorpay.Utils;

// Plain unit test. razorpay.enabled=false is the dev/test stub; enabled=true is driven
// through a RazorpayClient whose `subscriptions` is a FakeSubscriptionClient, so no test
// here reaches the real Razorpay API.
public class RazorpayGatewayServiceTest {

    /* Variables */

    private static final String SECRET = "whsec_test";

    private final FakeSubscriptionClient fake = new FakeSubscriptionClient();

    /* Public Methods */

    @Test
    public void createSubscription_disabled_returnsSyntheticIdAndNoCheckoutUrl() {
        RazorpaySubResult r = gateway(false).createSubscription("plan_x");
        assertTrue(r.subscriptionId().startsWith("local_sub_"));
        assertNull(r.checkoutUrl());
    }

    @Test
    public void createSubscription_enabled_sendsPlanAndMapsIdAndShortUrl() {
        fake.returned = new Subscription(new JSONObject().put("id", "sub_1").put("short_url", "https://rzp.io/x"));
        RazorpaySubResult r = gateway(true).createSubscription("plan_x");
        assertEquals("sub_1", r.subscriptionId());
        assertEquals("https://rzp.io/x", r.checkoutUrl());
        assertEquals("plan_x", fake.lastCreateRequest.getString("plan_id"));
        assertEquals(120, fake.lastCreateRequest.getInt("total_count"));
    }

    @Test
    public void createSubscription_enabledGatewayFails_throwsIllegalState() {
        fake.failure = new RazorpayException("boom");
        assertThrows(IllegalStateException.class, () -> gateway(true).createSubscription("plan_x"));
    }

    @Test
    public void cancelSubscription_disabled_isNoop() {
        gateway(false).cancelSubscription("sub_1");
        assertNull(fake.lastCancelledId);
    }

    @Test
    public void cancelSubscription_enabled_cancelsById() {
        gateway(true).cancelSubscription("sub_1");
        assertEquals("sub_1", fake.lastCancelledId);
    }

    @Test
    public void cancelSubscription_enabledGatewayFails_throwsIllegalState() {
        fake.failure = new RazorpayException("boom");
        assertThrows(IllegalStateException.class, () -> gateway(true).cancelSubscription("sub_1"));
    }

    @Test
    public void cancelSubscription_noClientYet_buildsRealClientAndSurfacesGatewayFailure() {
        // client() lazily builds the real SDK client; route its HTTP through a refused
        // local proxy so the call fails fast and never leaves the machine.
        ProxySelector original = ProxySelector.getDefault();
        ProxySelector.setDefault(new RefusingProxySelector());
        try {
            RazorpayGatewayService svc = new RazorpayGatewayService();
            ReflectionTestUtils.setField(svc, "enabled", true);
            ReflectionTestUtils.setField(svc, "keyId", "k");
            ReflectionTestUtils.setField(svc, "keySecret", "s");
            assertThrows(IllegalStateException.class, () -> svc.cancelSubscription("sub_1"));
            assertNotNull(ReflectionTestUtils.getField(svc, "client"));
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    @Test
    public void fetchSubscription_disabled_reportsActiveWithFuturePeriodEnd() {
        RazorpaySubStatus s = gateway(false).fetchSubscription("sub_1");
        assertEquals("active", s.status());
        assertTrue(s.currentPeriodEnd().isAfter(Instant.now()));
    }

    @Test
    public void fetchSubscription_enabledWithCurrentEnd_mapsEpochSeconds() {
        fake.returned = new Subscription(new JSONObject().put("status", "active").put("current_end", 1_800_000_000L));
        RazorpaySubStatus s = gateway(true).fetchSubscription("sub_1");
        assertEquals("active", s.status());
        assertEquals(Instant.ofEpochSecond(1_800_000_000L), s.currentPeriodEnd());
        assertEquals("sub_1", fake.lastFetchedId);
    }

    @Test
    public void fetchSubscription_enabledWithoutCurrentEnd_periodEndIsNull() {
        fake.returned = new Subscription(new JSONObject().put("status", "pending"));
        RazorpaySubStatus s = gateway(true).fetchSubscription("sub_1");
        assertEquals("pending", s.status());
        assertNull(s.currentPeriodEnd());
    }

    @Test
    public void fetchSubscription_enabledZeroCurrentEnd_periodEndIsNull() {
        fake.returned = new Subscription(new JSONObject().put("status", "pending").put("current_end", 0));
        assertNull(gateway(true).fetchSubscription("sub_1").currentPeriodEnd());
    }

    @Test
    public void fetchSubscription_enabledGatewayFails_throwsIllegalState() {
        fake.failure = new RazorpayException("boom");
        assertThrows(IllegalStateException.class, () -> gateway(true).fetchSubscription("sub_1"));
    }

    @Test
    public void verifySignature_disabled_acceptsAnything() {
        assertTrue(gateway(false).verifySignature(bytes("{}"), null));
        assertTrue(gateway(false).verifySignature(bytes("{}"), "garbage"));
    }

    @Test
    public void verifySignature_enabledNullSignature_rejected() {
        assertFalse(gateway(true).verifySignature(bytes("{}"), null));
    }

    @Test
    public void verifySignature_enabledValidSignature_accepted() throws Exception {
        String body = "{\"event\":\"subscription.charged\"}";
        assertTrue(gateway(true).verifySignature(bytes(body), Utils.getHash(body, SECRET)));
    }

    @Test
    public void verifySignature_enabledWrongSignature_rejected() {
        assertFalse(gateway(true).verifySignature(bytes("{}"), "deadbeef"));
    }

    @Test
    public void verifySignature_enabledEmptySecret_rejectedNotThrown() {
        RazorpayGatewayService svc = gateway(true);
        ReflectionTestUtils.setField(svc, "webhookSecret", "");
        assertFalse(svc.verifySignature(bytes("{}"), "deadbeef"));
    }

    /* Private Helper Methods */

    private RazorpayGatewayService gateway(boolean enabled) {
        RazorpayGatewayService svc = new RazorpayGatewayService();
        ReflectionTestUtils.setField(svc, "enabled", enabled);
        ReflectionTestUtils.setField(svc, "webhookSecret", SECRET);
        if (enabled) {
            try {
                RazorpayClient client = new RazorpayClient("k", "s");
                client.subscriptions = fake;
                ReflectionTestUtils.setField(svc, "client", client);
            } catch (RazorpayException e) {
                throw new IllegalStateException(e);
            }
        }
        return svc;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /* Test Doubles */

    // Every URI resolves to a local proxy nobody listens on.
    private static class RefusingProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // nothing to recover
        }
    }
}
