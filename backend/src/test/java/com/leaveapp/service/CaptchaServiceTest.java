package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;

// Plain unit test, no Spring context. Google's siteverify endpoint is a compile-time
// constant behind a static HttpClient, so there is no seam to fake a reply: the failure
// paths of the outbound call are driven instead by pointing the JVM's HTTPS proxy at a
// closed local port (connection refused -> IOException) and by interrupting the thread
// (InterruptedException). Both must fail closed.
public class CaptchaServiceTest {

    /* Variables */

    private final CaptchaService svc = new CaptchaService();

    private String savedHost;
    private String savedPort;

    @BeforeEach
    public void setUp() {
        savedHost = System.getProperty("https.proxyHost");
        savedPort = System.getProperty("https.proxyPort");
        ReflectionTestUtils.setField(svc, "secret", "test-secret");
        ReflectionTestUtils.setField(svc, "scoreThreshold", 0.5);
    }

    @AfterEach
    public void tearDown() {
        Thread.interrupted(); // never leak an interrupt flag into the next test
        restore("https.proxyHost", savedHost);
        restore("https.proxyPort", savedPort);
    }

    /* Public Methods */

    @Test
    public void verify_disabled_isNoopEvenWithNullTokenAndAction() {
        ReflectionTestUtils.setField(svc, "enabled", false);

        assertDoesNotThrow(() -> svc.verify(null, null));
        assertDoesNotThrow(() -> svc.verify("", "login"));
    }

    @Test
    public void verify_enabledWithNullToken_throwsBadRequest() {
        ReflectionTestUtils.setField(svc, "enabled", true);

        assertThrows(BadRequestException.class, () -> svc.verify(null, "login"));
    }

    @Test
    public void verify_enabledWithEmptyToken_throwsBadRequest() {
        ReflectionTestUtils.setField(svc, "enabled", true);

        assertThrows(BadRequestException.class, () -> svc.verify("", "login"));
    }

    @Test
    public void verify_enabledWithBlankToken_throwsBadRequest() {
        ReflectionTestUtils.setField(svc, "enabled", true);

        assertThrows(BadRequestException.class, () -> svc.verify("   ", "login"));
    }

    @Test
    public void verify_verifyCallCannotConnect_failsClosedWithBadRequest() throws IOException {
        ReflectionTestUtils.setField(svc, "enabled", true);
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(closedPort()));

        BadRequestException e = assertThrows(BadRequestException.class, () -> svc.verify("a-token", "login"));

        assertEquals("captcha verification failed", e.getMessage());
    }

    @Test
    public void verify_threadInterruptedDuringCall_failsClosedAndKeepsInterruptFlag() {
        ReflectionTestUtils.setField(svc, "enabled", true);
        Thread.currentThread().interrupt();

        BadRequestException e = assertThrows(BadRequestException.class, () -> svc.verify("a-token", "login"));

        assertEquals("captcha verification failed", e.getMessage());
        assertTrue(Thread.currentThread().isInterrupted()); // the interrupt is re-asserted, not swallowed
    }

    /* Private Helper Methods */

    // a port that was just free and is now closed: nothing listens, so connects are refused
    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
