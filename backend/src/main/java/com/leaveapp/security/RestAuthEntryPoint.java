package com.leaveapp.security;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaveapp.model.data.ErrorData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A filter-level 401 (rejected before DispatcherServlet, so GlobalExceptionHandler never
 * sees it) still needs to come back as {"summary": "..."} -- the frontend reads
 * body.summary on every non-2xx response. Uses its own ObjectMapper rather than the one
 * WebConfig configures, since that lives in the servlet context and SecurityConfig (root
 * context) can't reach it; timestamp is written as a String, not java.time.Instant, so no
 * extra Jackson module is needed here either.
 */
@Component
public class RestAuthEntryPoint implements AuthenticationEntryPoint {

    /* Variables */

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* Public Methods */

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
            throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorData body = new ErrorData("not authenticated");
        body.setCode("AUTHN_REQUIRED");
        body.setTraceId(MDC.get("requestId")); // set by RequestIdFilter, which runs ahead of this chain
        body.setTimestamp(Instant.now().toString());
        MAPPER.writeValue(res.getWriter(), body);
    }
}
