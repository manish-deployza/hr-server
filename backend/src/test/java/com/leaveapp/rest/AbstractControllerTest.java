package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;

// Plain unit test: the shared identity/role helpers every controller leans on, driven
// through a trivial subclass that re-exposes the protected methods.
public class AbstractControllerTest {

    /* Variables */

    private final Probe probe = new Probe();

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void requireUserId_present_returnsIt() {
        authenticate(7L, 3L, List.of(UserRole.HR));
        assertEquals(7L, probe.requireUserId());
    }

    @Test
    public void requireUserId_anonymousOrNullId_notAuthenticated() {
        assertThrows(NotAuthException.class, probe::requireUserId);
        authenticate(null, 3L, List.of(UserRole.HR));
        assertThrows(NotAuthException.class, probe::requireUserId);
    }

    @Test
    public void requireOrgId_present_returnsIt() {
        authenticate(7L, 3L, List.of(UserRole.HR));
        assertEquals(3L, probe.requireOrgId());
    }

    @Test
    public void requireOrgId_anonymousOrNullOrg_notAuthenticated() {
        assertThrows(NotAuthException.class, probe::requireOrgId);
        authenticate(7L, null, List.of(UserRole.HR));
        assertThrows(NotAuthException.class, probe::requireOrgId);
    }

    @Test
    public void hasRole_matchingRole_true() {
        authenticate(7L, 3L, List.of(UserRole.EMPLOYEE, UserRole.HR));
        assertTrue(probe.hasRole(UserRole.HR));
    }

    @Test
    public void hasRole_otherRole_false() {
        authenticate(7L, 3L, List.of(UserRole.EMPLOYEE));
        assertFalse(probe.hasRole(UserRole.HR));
    }

    @Test
    public void hasRole_nullRoles_false() {
        authenticate(7L, 3L, null);
        assertFalse(probe.hasRole(UserRole.HR));
    }

    @Test
    public void hasRole_anonymous_false() {
        assertFalse(probe.hasRole(UserRole.HR));
    }

    @Test
    public void userIdOrNull_returnsIdOrNull() {
        assertNull(probe.userIdOrNull());
        authenticate(7L, 3L, List.of(UserRole.HR));
        assertEquals(7L, probe.userIdOrNull());
    }

    @Test
    public void requireOrgMatch_sameOrg_passes() {
        probe.requireOrgMatch(3L, 3L, 9L);
    }

    @Test
    public void requireOrgMatch_otherOrg_notFoundNamingTarget() {
        NotFoundException e = assertThrows(NotFoundException.class, () -> probe.requireOrgMatch(3L, 4L, 9L));
        assertEquals("employee not found id=9", e.getMessage());
    }

    /* Private Helper Methods */

    private static void authenticate(Long userId, Long orgId, List<UserRole> roles) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(orgId);
        p.setRoles(roles);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    /* Test Doubles */

    private static final class Probe extends AbstractController {
    }
}
