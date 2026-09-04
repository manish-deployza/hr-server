package com.leaveapp.rest;

// import flow:
// AbstractController -> UserHelper (security, reads session-bound user id)
// AbstractController -> CookieUtil (util, reads the session cookie off the request)
// AbstractController -> NotAuthenticatedException (exception, thrown when no user id is present)
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;

import com.leaveapp.exception.NotAuthenticatedException;
import com.leaveapp.pojo.UserRole;
import com.leaveapp.security.UserHelper;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.util.CookieUtil;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Shared base for every *Controller: cookie read/write + the auth check they all need.
public abstract class AbstractController {

    /* Variables */

    // Cookie name/secure flag come from app.properties, not hardcoded, so
    // prod (HTTPS) and local dev (HTTP) can differ.
    @Value("${app.cookie.name}")
    private String cookieName;

    @Value("${app.cookie.secure}")
    private boolean cookieSec;

    /* Protected Methods */

    // Called at the top of any endpoint that requires a logged-in user.
    protected Long requireUserId() {
        Long userId = UserHelper.getUserId();
        if (userId == null) {
            throw new NotAuthenticatedException("not authenticated");
        }
        return userId;
    }

    // Tenant id straight off the JWT -- no DB hit. Endpoints scoped to "the caller's
    // own org" use this so there's no id in the URL to tamper with.
    protected Long requireOrgId() {
        Long orgId = UserHelper.getOrganizationId();
        if (orgId == null) {
            throw new NotAuthenticatedException("not authenticated");
        }
        return orgId;
    }

    // 403 (via GlobalExceptionHandler's AccessDeniedException handler) when the
    // JWT's role claim doesn't match. Roles aren't loaded as Spring authorities,
    // so this is the check point, not the filter chain.
    protected void requireRole(UserRole role) {
        UserPrincipal p = UserHelper.getPrincipal();
        if (p == null || p.getRoles() == null || !p.getRoles().contains(role)) {
            throw new AccessDeniedException("requires role " + role);
        }
    }

    // Non-throwing role check, for endpoints whose rules depend on both the caller's
    // role and the request body.
    protected boolean hasRole(UserRole role) {
        UserPrincipal p = UserHelper.getPrincipal();
        return p != null && p.getRoles() != null && p.getRoles().contains(role);
    }

    // requireRole for the "any one of these" case. Same null-principal handling.
    protected void requireAnyRole(UserRole... roles) {
        UserPrincipal p = UserHelper.getPrincipal();
        if (p != null && p.getRoles() != null) {
            for (UserRole role : roles) {
                if (p.getRoles().contains(role)) {
                    return;
                }
            }
        }
        throw new AccessDeniedException("requires role " + Arrays.toString(roles));
    }

    // Non-throwing identity read, for endpoints that only need to act (e.g. revoke a
    // session) when a valid principal happens to be present.
    protected Long userIdOrNull() {
        return UserHelper.getUserId();
    }

    protected void writeSessionCookie(HttpServletResponse res, String token, int maxAgeSec) {
        Cookie cookie = buildCookie(token, maxAgeSec);
        res.addCookie(cookie);
    }

    // maxAge 0 tells the browser to delete the cookie immediately -- used on logout.
    protected void clearSessionCookie(HttpServletResponse res) {
        Cookie cookie = buildCookie("", 0);
        res.addCookie(cookie);
    }

    protected String readSessionToken(HttpServletRequest req) {
        return CookieUtil.read(req, cookieName);
    }

    /* Private Helper Methods */

    private Cookie buildCookie(String value, int maxAgeSec) {
        Cookie cookie = new Cookie(cookieName, value);
        cookie.setHttpOnly(true); // not readable from JS -- mitigates XSS token theft
        cookie.setSecure(cookieSec);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSec);
        return cookie;
    }
}
