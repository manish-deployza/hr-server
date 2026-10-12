package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;

public class JwtServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    // parseOrNull now checks the session is still valid (UserService.findIfSessionValid),
    // so the round trip needs a real, persisted, active user -- not a bare transient one.
    @Test
    public void issueThenParse_roundTrips() {
        OrgPojo org = orgSvc.create("Acme", "acme-jwt-1", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "issue@acme.test",
            "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.ORG_ADMIN);

        String token = jwtSvc.issue(user);
        UserPrincipal principal = jwtSvc.parseOrNull(token);

        assertEquals(user.getId(), principal.getUserId());
        assertEquals(org.getId(), principal.getOrgId());
        assertEquals(UserRole.ORG_ADMIN, principal.getRoles().get(0));
    }

    @Test
    public void parseOrNull_garbageToken_returnsNull() {
        assertNull(jwtSvc.parseOrNull("not-a-real-token"));
    }

    @Test
    public void passwordResetToken_roundTrips() {
        assertEquals(99L, jwtSvc.parsePasswordReset(jwtSvc.issuePasswordReset(99L)));
    }

    @Test
    public void parsePasswordReset_rejectsSessionToken() {
        UserPojo user = new UserPojo();
        user.setId(1L);
        user.setOrgId(1L);
        user.setRole(UserRole.EMPLOYEE);
        assertNull(jwtSvc.parsePasswordReset(jwtSvc.issue(user)));
    }

    @Test
    public void parsePasswordReset_rejectsEmailVerifyToken() {
        assertNull(jwtSvc.parsePasswordReset(jwtSvc.issueEmailVerify(5L)));
    }

    @Test
    public void parsePasswordReset_garbageToken_returnsNull() {
        assertNull(jwtSvc.parsePasswordReset("not-a-real-token"));
    }

    @Test
    public void parseEmailVerify_rejectsPasswordResetToken() {
        assertNull(jwtSvc.parseEmailVerify(jwtSvc.issuePasswordReset(5L)));
    }
}
