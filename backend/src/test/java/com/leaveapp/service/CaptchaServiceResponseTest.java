package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;

// Plain unit test: the Google reply is canned through the overridable post(), covering
// every arm of the success / action / score decision and the request body it builds.
public class CaptchaServiceResponseTest {

    /* Variables */

    private final CannedCaptcha svc = new CannedCaptcha();

    {
        ReflectionTestUtils.setField(svc, "enabled", true);
        ReflectionTestUtils.setField(svc, "secret", "s&cret");
        ReflectionTestUtils.setField(svc, "scoreThreshold", 0.5);
    }

    /* Public Methods */

    @Test
    public void verify_successMatchingActionHighScore_passes_andBodyIsUrlEncoded() {
        svc.reply = "{\"success\":true,\"action\":\"login\",\"score\":0.9}";
        assertDoesNotThrow(() -> svc.verify("t k", "login"));
        assertEquals("secret=s%26cret&response=t+k", svc.sentBody);
    }

    @Test
    public void verify_scoreExactlyAtThreshold_passes() {
        svc.reply = "{\"success\":true,\"action\":\"login\",\"score\":0.5}";
        assertDoesNotThrow(() -> svc.verify("t", "login"));
    }

    @Test
    public void verify_scoreBelowThreshold_rejected() {
        svc.reply = "{\"success\":true,\"action\":\"login\",\"score\":0.49}";
        assertThrows(BadRequestException.class, () -> svc.verify("t", "login"));
    }

    @Test
    public void verify_successFalse_rejected() {
        svc.reply = "{\"success\":false,\"action\":\"login\",\"score\":0.9}";
        assertThrows(BadRequestException.class, () -> svc.verify("t", "login"));
    }

    @Test
    public void verify_actionMismatch_rejected() {
        svc.reply = "{\"success\":true,\"action\":\"signup\",\"score\":0.9}";
        assertThrows(BadRequestException.class, () -> svc.verify("t", "login"));
    }

    @Test
    public void verify_missingFields_failClosed() {
        svc.reply = "{}";
        assertThrows(BadRequestException.class, () -> svc.verify("t", "login"));
    }

    @Test
    public void verify_malformedJson_failClosed() {
        svc.reply = "<html>not json";
        assertThrows(BadRequestException.class, () -> svc.verify("t", "login"));
    }

    @Test
    public void post_realHttpCall_postsFormBodyAndReturnsReply() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/siteverify", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"success\":true,\"action\":\"login\",\"score\":0.9}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            CaptchaService real = new CaptchaService();
            ReflectionTestUtils.setField(real, "enabled", true);
            ReflectionTestUtils.setField(real, "secret", "sec");
            ReflectionTestUtils.setField(real, "scoreThreshold", 0.5);
            ReflectionTestUtils.setField(real, "verifyUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify");

            assertDoesNotThrow(() -> real.verify("tok", "login"));
            assertEquals("secret=sec&response=tok", received.get());
        } finally {
            server.stop(0);
        }
    }

    /* Test Doubles */

    private static final class CannedCaptcha extends CaptchaService {
        private String reply;
        private String sentBody;

        @Override
        protected String post(String body) throws IOException {
            sentBody = body;
            return reply;
        }
    }
}
