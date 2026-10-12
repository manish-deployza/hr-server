package com.leaveapp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.leaveapp.filter.JwtAuthFilter;
import com.leaveapp.security.RestAuthEntryPoint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Builds the real SecurityConfig inside a minimal web application context (MockServletContext,
// @EnableWebMvc for the MVC request matchers, a stub JwtAuthFilter so no DB/JWT is needed)
// and drives MockHttpServletRequests through the resulting springSecurityFilterChain:
// the public/authenticated split for every permitAll route and its HTTP method, the filter-
// level 401 body, stateless sessions, CSRF off, the CORS policy (default and overridden
// origins, credentials, allowed methods/headers) and that @EnableMethodSecurity is live.
public class SecurityConfigTest {

    /* Variables */

    private static final String ORIGIN = "http://localhost:8080";

    private AnnotationConfigWebApplicationContext ctx;

    /* Public Methods */

    @AfterEach
    public void close() {
        SecurityContextHolder.clearContext();
        if (ctx != null) {
            ctx.close();
        }
    }

    // ---- public / authenticated split ----

    @Test
    public void publicRoutes_reachTheApplicationWithoutAuthentication() throws Exception {
        start(null);
        String[][] publicRoutes = {
            { "POST", "/api/auth/signup" }, { "POST", "/api/auth/login" }, { "POST", "/api/auth/logout" },
            { "POST", "/api/auth/forgot-password" }, { "POST", "/api/auth/reset-password" },
            { "POST", "/api/auth/set-password" }, { "GET", "/api/auth/verify-email" },
            { "POST", "/api/billing/webhook/razorpay" } };
        for (String[] r : publicRoutes) {
            Result out = call(r[0], r[1], null);
            assertTrue(out.reached, r[0] + " " + r[1] + " should reach the app");
            assertEquals(200, out.res.getStatus(), r[0] + " " + r[1]);
        }
    }

    @Test
    public void publicRoutes_wrongHttpMethod_requireAuthentication() throws Exception {
        start(null);
        String[][] wrongMethod = {
            { "GET", "/api/auth/login" }, { "GET", "/api/auth/signup" }, { "POST", "/api/auth/verify-email" },
            { "GET", "/api/billing/webhook/razorpay" }, { "PUT", "/api/auth/login" } };
        for (String[] r : wrongMethod) {
            Result out = call(r[0], r[1], null);
            assertTrue(!out.reached, r[0] + " " + r[1] + " must not reach the app");
            assertEquals(401, out.res.getStatus(), r[0] + " " + r[1]);
        }
    }

    @Test
    public void protectedRoute_anonymous_gets401JsonFromEntryPoint() throws Exception {
        start(null);

        Result out = call("GET", "/api/employees/me", null);

        assertTrue(!out.reached);
        assertEquals(401, out.res.getStatus());
        assertTrue(out.res.getContentAsString().contains("\"code\":\"AUTHN_REQUIRED\""), out.res.getContentAsString());
        assertTrue(out.res.getContentAsString().contains("\"summary\":\"not authenticated\""));
    }

    @Test
    public void otherPaths_anonymous_alsoRequireAuthentication() throws Exception {
        start(null);
        assertEquals(401, call("GET", "/anything-else", null).res.getStatus());
        assertEquals(401, call("POST", "/api/billing/subscribe", null).res.getStatus());
    }

    @Test
    public void protectedRoute_authenticatedByTheAuthFilter_reachesTheApp() throws Exception {
        start(null);

        Result out = call("GET", "/api/employees/me", auth());

        assertTrue(out.reached);
        assertEquals(200, out.res.getStatus());
    }

    @Test
    public void authFilter_runsBeforeUsernamePasswordFilter_soItsAuthenticationIsHonoured() throws Exception {
        start(null);
        StubAuthFilter filter = ctx.getBean(StubAuthFilter.class);

        call("GET", "/api/employees/me", auth());

        assertEquals(1, filter.calls);
    }

    // ---- session / CSRF ----

    @Test
    public void sessions_neverCreated() throws Exception {
        start(null);

        Result out = call("GET", "/api/employees/me", auth());

        assertNull(out.req.getSession(false));
    }

    @Test
    public void csrf_disabled_postWithoutTokenIsAccepted() throws Exception {
        start(null);

        Result out = call("POST", "/api/leave-requests", auth());

        assertTrue(out.reached);
        assertEquals(200, out.res.getStatus());
    }

    // ---- CORS ----

    @Test
    public void cors_defaultOrigin_preflightAllowedWithCredentialsAndMethods() throws Exception {
        start(null);

        MockHttpServletRequest req = preflight("/api/leave-requests", ORIGIN, "PATCH", "Content-Type");
        MockHttpServletResponse res = run(req).res;

        assertEquals(200, res.getStatus());
        assertEquals(ORIGIN, res.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", res.getHeader("Access-Control-Allow-Credentials"));
        assertEquals("GET,POST,PATCH,PUT,DELETE,OPTIONS", res.getHeader("Access-Control-Allow-Methods"));
        assertEquals("Content-Type", res.getHeader("Access-Control-Allow-Headers"));
    }

    @Test
    public void cors_unknownOrigin_rejected() throws Exception {
        start(null);

        MockHttpServletResponse res = run(preflight("/api/leave-requests", "https://evil.test", "POST", "Content-Type")).res;

        assertEquals(403, res.getStatus());
        assertNull(res.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    public void cors_headerOutsideAllowList_rejected() throws Exception {
        start(null);

        MockHttpServletResponse res = run(preflight("/api/leave-requests", ORIGIN, "POST", "X-Custom")).res;

        assertEquals(403, res.getStatus());
    }

    @Test
    public void cors_methodOutsideAllowList_rejected() throws Exception {
        start(null);

        MockHttpServletResponse res = run(preflight("/api/leave-requests", ORIGIN, "TRACE", "Content-Type")).res;

        assertEquals(403, res.getStatus());
    }

    @Test
    public void cors_configuredOrigins_commaSeparatedListReplacesTheDefault() throws Exception {
        start("https://a.test,https://b.test");

        assertEquals(200, run(preflight("/api/leave-requests", "https://a.test", "POST", "Content-Type")).res.getStatus());
        assertEquals(200, run(preflight("/api/leave-requests", "https://b.test", "POST", "Content-Type")).res.getStatus());
        assertEquals(403, run(preflight("/api/leave-requests", ORIGIN, "POST", "Content-Type")).res.getStatus());
    }

    @Test
    public void cors_actualRequestFromAllowedOrigin_getsAllowOriginHeader() throws Exception {
        start(null);

        MockHttpServletRequest req = request("GET", "/api/employees/me");
        req.addHeader("Origin", ORIGIN);
        Result out = run(req);

        assertEquals(ORIGIN, out.res.getHeader("Access-Control-Allow-Origin"));
        assertEquals(401, out.res.getStatus()); // CORS headers do not substitute for authentication
    }

    @Test
    public void corsSource_appliesOnlyToApiPaths() {
        start(null);
        CorsConfigurationSource source = ctx.getBean("corsConfigurationSource", CorsConfigurationSource.class);

        CorsConfiguration api = source.getCorsConfiguration(request("GET", "/api/employees/me"));
        CorsConfiguration other = source.getCorsConfiguration(request("GET", "/index.html"));

        assertNotNull(api);
        assertEquals(List.of(ORIGIN), api.getAllowedOrigins());
        assertEquals(Boolean.TRUE, api.getAllowCredentials());
        assertNull(other);
    }

    // ---- method security ----

    @Test
    public void methodSecurity_enabled_preAuthorizeIsEnforcedOnBeans() {
        start(null);
        Guarded guarded = ctx.getBean(Guarded.class);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
        assertThrows(AccessDeniedException.class, guarded::adminOnly);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN"))));
        assertEquals("ok", guarded.adminOnly());
    }

    /* Private Helper Methods */

    private void start(String allowedOrigins) {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        if (allowedOrigins != null) {
            ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("app.cors.allowed-origins", allowedOrigins)));
        }
        // Registered as a ready-made singleton so JwtAuthFilter's @Autowired fields are never resolved.
        ctx.addBeanFactoryPostProcessor(bf -> bf.registerSingleton("authFilter", new StubAuthFilter()));
        ctx.register(TestWebConfig.class, SecurityConfig.class);
        ctx.refresh();
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
    }

    private Result call(String method, String uri, UsernamePasswordAuthenticationToken authentication)
            throws ServletException, IOException {
        MockHttpServletRequest req = request(method, uri);
        if (authentication != null) {
            req.setAttribute(StubAuthFilter.AUTH_ATTR, authentication);
        }
        return run(req);
    }

    private Result run(MockHttpServletRequest req) throws ServletException, IOException {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain terminal = new MockFilterChain();
        ctx.getBean("springSecurityFilterChain", FilterChainProxy.class).doFilter(req, res, terminal);
        return new Result(req, res, terminal.getRequest() != null);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(ctx.getServletContext(), method, uri);
        req.setServletPath(uri);
        return req;
    }

    private MockHttpServletRequest preflight(String uri, String origin, String method, String headers) {
        MockHttpServletRequest req = request("OPTIONS", uri);
        req.addHeader("Origin", origin);
        req.addHeader("Access-Control-Request-Method", method);
        req.addHeader("Access-Control-Request-Headers", headers);
        return req;
    }

    /* Test Doubles */

    private static final class Result {
        private final MockHttpServletRequest req;
        private final MockHttpServletResponse res;
        private final boolean reached;

        private Result(MockHttpServletRequest req, MockHttpServletResponse res, boolean reached) {
            this.req = req;
            this.res = res;
            this.reached = reached;
        }
    }

    // Stands in for the real JwtAuthFilter (covered by JwtAuthFilterTest): authenticates the
    // request from a test-set attribute instead of a cookie, so no database or JWT is needed.
    // JwtAuthFilter's collaborators are @Autowired fields, never touched by this override.
    public static class StubAuthFilter extends JwtAuthFilter {
        static final String AUTH_ATTR = "test.auth";
        private int calls;

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            calls++;
            Object a = req.getAttribute(AUTH_ATTR);
            if (a instanceof UsernamePasswordAuthenticationToken token) {
                SecurityContextHolder.getContext().setAuthentication(token);
            }
            chain.doFilter(req, res);
        }
    }

    public static class Guarded {
        @PreAuthorize("hasRole('ORG_ADMIN')")
        public String adminOnly() {
            return "ok";
        }
    }

    // Deliberately not @Configuration: TestConfig component-scans com.leaveapp, and a scanned
    // @EnableWebMvc class would break every non-web repository-test context.
    @EnableWebMvc
    public static class TestWebConfig {
        @Bean
        public RestAuthEntryPoint entryPoint() {
            return new RestAuthEntryPoint();
        }

        @Bean
        public Guarded guarded() {
            return new Guarded();
        }
    }
}
