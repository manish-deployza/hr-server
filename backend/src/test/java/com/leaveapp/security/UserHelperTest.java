package com.leaveapp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.leaveapp.model.enums.UserRole;

// Plain unit test: reads the principal off SecurityContextHolder; anything that is not
// our UserPrincipal (no auth, foreign principal type) reads as "no user".
public class UserHelperTest {

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void noAuthentication_everythingNull() {
        assertNull(UserHelper.getPrincipal());
        assertNull(UserHelper.getUserId());
        assertNull(UserHelper.getOrgId());
    }

    @Test
    public void foreignPrincipalType_everythingNull() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));
        assertNull(UserHelper.getPrincipal());
        assertNull(UserHelper.getUserId());
        assertNull(UserHelper.getOrgId());
    }

    @Test
    public void userPrincipal_exposesIds() {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(4L);
        p.setOrgId(8L);
        p.setRoles(List.of(UserRole.HR));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));

        assertSame(p, UserHelper.getPrincipal());
        assertEquals(4L, UserHelper.getUserId());
        assertEquals(8L, UserHelper.getOrgId());
    }
}
