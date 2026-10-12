package com.leaveapp.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.security.TenantContext;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.JwtService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;

// Covers the four behaviours this filter exists for: an authenticated request gets a
// principal, authorities and TenantContext; an unauthenticated one is left anonymous; and
// both ThreadLocals are cleared once doFilter returns, whether or not the chain throws.
public class JwtAuthFilterTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private JwtAuthFilter filter;

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    // Safety net: if an assertion above fails mid-test, don't let the ThreadLocal leak
    // into whichever test runs next on this thread.
    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    public void doFilter_validToken_setsPrincipalAuthoritiesAndTenant() throws Exception {
        OrgPojo org = orgSvc.create("Acme", "acme-filter-1", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "valid@acme.test",
            "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.HR);
        String token = jwtSvc.issue(user);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("session_token", token));
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Assertions run from inside the chain: doFilter clears both ThreadLocals in its
        // own finally block immediately after chain.doFilter returns.
        UserPrincipal[] capturedPrincipal = new UserPrincipal[1];
        Long[] capturedTenant = new Long[1];
        FilterChain chain = (q, r) -> {
            capturedPrincipal[0] =
                (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            capturedTenant[0] = TenantContext.get();
        };

        filter.doFilter(req, res, chain);

        assertEquals(user.getId(), capturedPrincipal[0].getUserId());
        assertEquals(org.getId(), capturedTenant[0]);
        assertTrue(capturedPrincipal[0].getRoles().contains(UserRole.HR));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContext.get());
    }

    @Test
    public void doFilter_validToken_grantsRolePrefixedAuthority() throws Exception {
        OrgPojo org = orgSvc.create("Acme", "acme-filter-2", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "role@acme.test",
            "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.ORG_ADMIN);
        String token = jwtSvc.issue(user);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("session_token", token));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean[] hasRoleAuthority = { false };
        FilterChain chain = (q, r) -> {
            for (GrantedAuthority a : SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
                if ("ROLE_ORG_ADMIN".equals(a.getAuthority())) {
                    hasRoleAuthority[0] = true;
                }
            }
        };

        filter.doFilter(req, res, chain);

        assertTrue(hasRoleAuthority[0]);
    }

    @Test
    public void doFilter_pendingUser_getsOnlyOnboardingAuthority() throws Exception {
        OrgPojo org = orgSvc.create("Acme", "acme-filter-pend", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "pend@acme.test",
            "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE, UserStatus.PENDING);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("session_token", jwtSvc.issue(user)));

        List<String> granted = new ArrayList<>();
        FilterChain chain = (q, r) -> {
            for (GrantedAuthority a : SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
                granted.add(a.getAuthority());
            }
        };
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertEquals(List.of("ROLE_ONBOARDING"), granted); // no ROLE_EMPLOYEE: every other endpoint rejects it
    }

    @Test
    public void doFilter_noCookie_leavesContextAnonymous() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertAnonymousThroughChain(req, res);
    }

    @Test
    public void doFilter_garbageToken_leavesContextAnonymous() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("session_token", "not-a-real-token"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertAnonymousThroughChain(req, res);
    }

    @Test
    public void doFilter_revokedToken_leavesContextAnonymous() throws Exception {
        OrgPojo org = orgSvc.create("Acme", "acme-filter-3", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "revoked@acme.test",
            "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE);
        String token = jwtSvc.issue(user);
        // findIfSessionValid compares at whole-second precision (see UserService.findIfSessionValid) --
        // cross a full second boundary so the revoke is unambiguously after the token's iat.
        Thread.sleep(1100);
        userSvc.revokeSessions(user.getId());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("session_token", token));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertAnonymousThroughChain(req, res);
    }

    @Test
    public void doFilter_chainThrows_stillClearsContext() throws Exception {
        OrgPojo org = orgSvc.create("Acme", "acme-filter-4", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "throws@acme.test",
            "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE);
        String token = jwtSvc.issue(user);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("session_token", token));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (q, r) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(req, res, chain));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContext.get());
    }

    /* Private Helper Methods */

    private void assertAnonymousThroughChain(MockHttpServletRequest req, MockHttpServletResponse res)
            throws Exception {
        boolean[] chainCalled = { false };
        FilterChain chain = (q, r) -> {
            chainCalled[0] = true;
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertNull(TenantContext.get());
        };

        filter.doFilter(req, res, chain);

        assertTrue(chainCalled[0]);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(TenantContext.get());
    }
}
