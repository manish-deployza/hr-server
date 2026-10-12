package com.leaveapp.rest;

// import flow:
// AbstractController -> UserHelper (security, reads session-bound user id)
// AbstractController -> NotAuthException (exception, thrown when no user id is present)
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserHelper;
import com.leaveapp.security.UserPrincipal;

// Shared base for every *Controller: the identity/role reads they all need. Cookie
// transport lives in AuthController only -- it's the one controller that issues or
// clears the session cookie. Role/route gating lives declaratively as @PreAuthorize
// on each *Controller (SecurityConfig only enforces "must be authenticated"); hasRole()
// below is for in-method business logic that depends on the caller's role (e.g.
// deciding what a response includes or which target rows are manageable), not for gating.
public abstract class AbstractController {

    /* Protected Methods */

    // Called at the top of any endpoint that requires a logged-in user.
    protected Long requireUserId() {
        Long userId = UserHelper.getUserId();
        if (userId == null) {
            throw new NotAuthException("not authenticated");
        }
        return userId;
    }

    // Tenant id straight off the JWT -- no DB hit. Endpoints scoped to "the caller's
    // own org" use this so there's no id in the URL to tamper with.
    protected Long requireOrgId() {
        Long orgId = UserHelper.getOrgId();
        if (orgId == null) {
            throw new NotAuthException("not authenticated");
        }
        return orgId;
    }

    // Non-throwing role check, for endpoints whose rules depend on both the caller's
    // role and the request body.
    protected boolean hasRole(UserRole role) {
        UserPrincipal p = UserHelper.getPrincipal();
        return p != null && p.getRoles() != null && p.getRoles().contains(role);
    }

    // Non-throwing identity read, for endpoints that only need to act (e.g. revoke a
    // session) when a valid principal happens to be present.
    protected Long userIdOrNull() {
        return UserHelper.getUserId();
    }

    // Object-level org-ownership check shared by every controller that looks up a
    // target row by id: cross-org (or already-deleted) targets get a 404, never a 403
    // -- same no-existence-leak posture everywhere this is used.
    protected void requireOrgMatch(Long callerOrgId, Long targetOrgId, Long targetId) {
        if (!callerOrgId.equals(targetOrgId)) {
            throw new NotFoundException("employee not found id=" + targetId);
        }
    }
}
