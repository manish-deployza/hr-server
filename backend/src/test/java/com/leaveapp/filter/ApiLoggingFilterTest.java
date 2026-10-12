package com.leaveapp.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

// Plain unit test: the filter only logs, so what matters is that it never swallows or
// alters the request -- the chain is always invoked exactly once, for /api/ and non-/api/
// URIs alike, and a failing chain still propagates (the "end" log sits in a finally).
public class ApiLoggingFilterTest {

    /* Variables */

    private final ApiLoggingFilter filter = new ApiLoggingFilter();
    private final MockHttpServletResponse res = new MockHttpServletResponse();

    /* Public Methods */

    @Test
    public void apiRequest_passesThroughChainOnce() throws ServletException, IOException {
        RecordingChain chain = new RecordingChain(null);

        filter.doFilter(request("GET", "/api/employees/me"), res, chain);

        assertEquals(List.of("/api/employees/me"), chain.uris);
    }

    @Test
    public void apiRequest_chainSetsStatus_filterLeavesItAlone() throws ServletException, IOException {
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(404);

        filter.doFilter(request("POST", "/api/anything"), res, chain);

        assertEquals(404, res.getStatus());
    }

    @Test
    public void apiRequest_chainThrows_exceptionStillPropagates() {
        RecordingChain chain = new RecordingChain(new ServletException("boom"));

        assertThrows(ServletException.class, () -> filter.doFilter(request("GET", "/api/x"), res, chain));

        assertEquals(1, chain.uris.size());
    }

    @Test
    public void nonApiRequest_passesThroughChainOnce() throws ServletException, IOException {
        RecordingChain chain = new RecordingChain(null);

        filter.doFilter(request("GET", "/index.html"), res, chain);

        assertEquals(List.of("/index.html"), chain.uris);
    }

    @Test
    public void apiPrefixWithoutTrailingSlash_countsAsNonApi() throws ServletException, IOException {
        RecordingChain chain = new RecordingChain(null);

        filter.doFilter(request("GET", "/api"), res, chain);

        assertEquals(List.of("/api"), chain.uris);
    }

    @Test
    public void nonApiRequest_chainThrows_exceptionPropagates() {
        RecordingChain chain = new RecordingChain(new IOException("io"));

        assertThrows(IOException.class, () -> filter.doFilter(request("GET", "/static/a.js"), res, chain));
    }

    /* Private Helper Methods */

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    /* Test Doubles */

    private static final class RecordingChain implements FilterChain {
        private final List<String> uris = new ArrayList<>();
        private final Exception failure;

        private RecordingChain(Exception failure) {
            this.failure = failure;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                throws IOException, ServletException {
            uris.add(((MockHttpServletRequest) req).getRequestURI());
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof ServletException se) {
                throw se;
            }
        }
    }
}
