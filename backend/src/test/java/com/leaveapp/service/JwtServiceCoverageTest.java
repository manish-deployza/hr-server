package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.security.UserPrincipal;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// Gap-filler for JwtServiceTest: the set-password token, expired / tampered / wrong-purpose
// tokens on every parser, and each way parseOrNull refuses a session token.
public class JwtServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    @Test
    public void expirySeconds_isConfiguredMinutesTimes60() {
        assertEquals(3600, jwtSvc.expirySeconds()); // test.properties: app.jwt.expiry-minutes=60
    }

    @Test
    public void setPasswordToken_roundTrips() {
        assertEquals(42L, jwtSvc.parseSetPassword(jwtSvc.issueSetPassword(42L)));
    }

    @Test
    public void parseSetPassword_rejectsOtherPurposesAndSessionTokens() {
        assertNull(jwtSvc.parseSetPassword(jwtSvc.issuePasswordReset(5L)));
        assertNull(jwtSvc.parseSetPassword(jwtSvc.issueEmailVerify(5L)));
        assertNull(jwtSvc.parseSetPassword(sessionToken(5L, "ADMIN", future())));
    }

    @Test
    public void parseSetPassword_garbageAndEmptyAndNull_returnNull() {
        assertNull(jwtSvc.parseSetPassword("not-a-real-token"));
        assertNull(jwtSvc.parseSetPassword(""));
        assertNull(jwtSvc.parseSetPassword(null));
    }

    @Test
    public void otherPurposeTokens_rejectedByEveryParser() {
        assertNull(jwtSvc.parseEmailVerify(jwtSvc.issueSetPassword(7L)));
        assertNull(jwtSvc.parsePasswordReset(jwtSvc.issueSetPassword(7L)));
        assertNull(jwtSvc.parseEmailVerify(sessionToken(7L, "EMPLOYEE", future())));
        assertNull(jwtSvc.parseEmailVerify("not-a-real-token"));
        assertEquals(7L, jwtSvc.parseEmailVerify(jwtSvc.issueEmailVerify(7L)));
    }

    @Test
    public void expiredTokens_rejectedByEveryParser() {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        assertNull(jwtSvc.parseEmailVerify(purposeToken(3L, "email-verify", past, secret())));
        assertNull(jwtSvc.parsePasswordReset(purposeToken(3L, "pwd-reset", past, secret())));
        assertNull(jwtSvc.parseSetPassword(purposeToken(3L, "set-password", past, secret())));
        assertNull(jwtSvc.parseOrNull(sessionToken(3L, "EMPLOYEE", past)));
    }

    @Test
    public void tokensSignedWithAnotherKey_rejectedByEveryParser() {
        String otherKey = "a-completely-different-secret-key-of-32-plus-bytes!!";
        assertNull(jwtSvc.parseEmailVerify(purposeToken(3L, "email-verify", future(), otherKey)));
        assertNull(jwtSvc.parsePasswordReset(purposeToken(3L, "pwd-reset", future(), otherKey)));
        assertNull(jwtSvc.parseSetPassword(purposeToken(3L, "set-password", future(), otherKey)));
        assertNull(jwtSvc.parseOrNull(Jwts.builder().subject("3").claim("orgId", 1L).claim("role", "EMPLOYEE")
            .issuedAt(new Date()).expiration(future()).signWith(Keys.hmacShaKeyFor(otherKey.getBytes(StandardCharsets.UTF_8)))
            .compact()));
    }

    @Test
    public void purposeTokenWithNonNumericSubject_returnsNull() {
        assertNull(jwtSvc.parseEmailVerify(purposeToken("abc", "email-verify", future(), secret())));
        assertNull(jwtSvc.parsePasswordReset(purposeToken("abc", "pwd-reset", future(), secret())));
        assertNull(jwtSvc.parseSetPassword(purposeToken("abc", "set-password", future(), secret())));
    }

    @Test
    public void parseOrNull_activeUser_isNotOnboarding() {
        UserPojo user = aUser("acme-jwtc-1", "active@acme.test", UserStatus.ACTIVE);

        UserPrincipal p = jwtSvc.parseOrNull(jwtSvc.issue(user));

        assertFalse(p.isOnboarding());
    }

    @Test
    public void parseOrNull_pendingUser_isScopedToOnboarding() {
        UserPojo user = aUser("acme-jwtc-2", "pending@acme.test", UserStatus.PENDING);

        UserPrincipal p = jwtSvc.parseOrNull(jwtSvc.issue(user));

        assertTrue(p.isOnboarding());
    }

    @Test
    public void parseOrNull_unknownUser_returnsNull() {
        assertNull(jwtSvc.parseOrNull(sessionToken(987654L, "EMPLOYEE", future())));
    }

    @Test
    public void parseOrNull_disabledUser_returnsNull() {
        UserPojo user = aUser("acme-jwtc-3", "exited@acme.test", UserStatus.DISABLED);

        assertNull(jwtSvc.parseOrNull(jwtSvc.issue(user)));
    }

    @Test
    public void parseOrNull_revokedSession_returnsNull() {
        UserPojo user = aUser("acme-jwtc-4", "revoked@acme.test", UserStatus.ACTIVE);
        String token = jwtSvc.issue(user);
        user.setTokensValidAfter(Instant.now().plusSeconds(3600));

        assertNull(jwtSvc.parseOrNull(token));
    }

    @Test
    public void parseOrNull_unknownRoleClaim_returnsNull() {
        UserPojo user = aUser("acme-jwtc-5", "badrole@acme.test", UserStatus.ACTIVE);

        assertNull(jwtSvc.parseOrNull(sessionToken(user.getId(), "NOT_A_ROLE", future())));
    }

    @Test
    public void parseOrNull_nonNumericSubject_returnsNull() {
        assertNull(jwtSvc.parseOrNull(Jwts.builder().subject("abc").claim("role", "EMPLOYEE")
            .issuedAt(new Date()).expiration(future()).signWith(Keys.hmacShaKeyFor(secret().getBytes(StandardCharsets.UTF_8)))
            .compact()));
    }

    @Test
    public void parseOrNull_nullToken_returnsNull() {
        assertNull(jwtSvc.parseOrNull(null));
    }

    /* Private Helper Methods */

    private UserPojo aUser(String orgKey, String email, UserStatus status) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        return userSvc.createUser(org.getId(), email, "s3cret!!".getBytes(StandardCharsets.UTF_8),
            UserRole.EMPLOYEE, status);
    }

    private String secret() {
        Object target = AopTestUtils.getTargetObject(jwtSvc);
        return (String) ReflectionTestUtils.getField(target, "secret");
    }

    private static Date future() {
        return new Date(System.currentTimeMillis() + 3_600_000);
    }

    // a session-shaped token (orgId + role claims, no purpose) signed with the real key
    private String sessionToken(Long userId, String role, Date exp) {
        return Jwts.builder().subject(String.valueOf(userId)).claim("orgId", 1L).claim("role", role)
            .issuedAt(new Date(System.currentTimeMillis() - 120_000)).expiration(exp)
            .signWith(Keys.hmacShaKeyFor(secret().getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String purposeToken(Object subject, String purpose, Date exp, String key) {
        return Jwts.builder().subject(String.valueOf(subject)).claim("purpose", purpose)
            .issuedAt(new Date(System.currentTimeMillis() - 120_000)).expiration(exp)
            .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
