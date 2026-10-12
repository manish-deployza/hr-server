package com.leaveapp.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

// Plain unit test: every request gets a fresh UUID, visible to the rest of the chain both
// as a request attribute and as the "requestId" MDC key, and the MDC key never outlives
// the request -- including when the chain throws.
public class RequestIdFilterTest {

    /* Variables */

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletResponse res = new MockHttpServletResponse();

    /* Public Methods */

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    @Test
    public void request_getsUuidAsAttributeAndMdcDuringChain_thenMdcCleared() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        String[] seenInChain = new String[2];
        FilterChain chain = (rq, rs) -> {
            seenInChain[0] = (String) rq.getAttribute(RequestIdFilter.REQ_ID_ATTR);
            seenInChain[1] = MDC.get("requestId");
        };

        filter.doFilter(req, res, chain);

        assertNotNull(seenInChain[0]);
        UUID.fromString(seenInChain[0]); // throws if not a UUID
        assertEquals(seenInChain[0], seenInChain[1]);
        assertEquals(seenInChain[0], req.getAttribute(RequestIdFilter.REQ_ID_ATTR));
        assertNull(MDC.get("requestId"));
    }

    @Test
    public void eachRequest_getsDifferentId() throws ServletException, IOException {
        MockHttpServletRequest a = new MockHttpServletRequest();
        MockHttpServletRequest b = new MockHttpServletRequest();

        filter.doFilter(a, res, (rq, rs) -> { });
        filter.doFilter(b, res, (rq, rs) -> { });

        assertNotEquals(a.getAttribute(RequestIdFilter.REQ_ID_ATTR), b.getAttribute(RequestIdFilter.REQ_ID_ATTR));
    }

    @Test
    public void chainThrows_mdcStillCleared_andExceptionPropagates() {
        FilterChain chain = (rq, rs) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(new MockHttpServletRequest(), res, chain));

        assertNull(MDC.get("requestId"));
    }
}
