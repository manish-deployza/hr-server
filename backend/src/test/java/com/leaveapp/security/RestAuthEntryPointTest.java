package com.leaveapp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Plain unit test: the filter-level 401 body the frontend reads -- status, JSON content
// type, the fixed summary/code, the request id from MDC when RequestIdFilter set one (and
// a null traceId when not), and a parseable UTC timestamp. The stack trace and details of
// the triggering exception must never leak.
public class RestAuthEntryPointTest {

    /* Variables */

    private final RestAuthEntryPoint entryPoint = new RestAuthEntryPoint();
    private final MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/employees/me");
    private final MockHttpServletResponse res = new MockHttpServletResponse();

    /* Public Methods */

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    @Test
    public void commence_writes401JsonWithTraceIdFromMdc() throws IOException {
        MDC.put("requestId", "req-123");
        Instant before = Instant.now();

        entryPoint.commence(req, res, new BadCredentialsException("secret internal reason"));

        assertEquals(401, res.getStatus());
        assertTrue(res.getContentType().startsWith("application/json"), res.getContentType());
        JsonNode body = new ObjectMapper().readTree(res.getContentAsString());
        assertEquals("not authenticated", body.get("summary").asText());
        assertEquals("AUTHN_REQUIRED", body.get("code").asText());
        assertEquals("req-123", body.get("traceId").asText());
        assertFalse(Instant.parse(body.get("timestamp").asText()).isBefore(before.minusSeconds(1)));
        assertFalse(res.getContentAsString().contains("secret internal reason"));
    }

    @Test
    public void commence_noRequestId_traceIdIsNull() throws IOException {
        entryPoint.commence(req, res, new BadCredentialsException("x"));

        JsonNode body = new ObjectMapper().readTree(res.getContentAsString());
        assertTrue(body.get("traceId").isNull());
        assertNull(MDC.get("requestId"));
        assertEquals(401, res.getStatus());
    }
}
