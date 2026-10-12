package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;

// Gap-filler for UserServiceTest's login/password coverage: lockout windows, accounts
// with no password, the failed-attempt counter, and the password screen's edge cases.
public class CredentialServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private CredentialService credSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void verifyLogin_lockedUntilInFuture_throwsEvenWithCorrectPassword() {
        UserPojo user = anActiveUser("acme-credc-1", "locked@acme.test");
        user.setLockedUntil(Instant.now().plusSeconds(600));

        assertThrows(NotAuthException.class, //
            () -> credSvc.verifyLogin(user.getOrgId(), "locked@acme.test", pwd("s3cret!!")));
    }

    @Test
    public void verifyLogin_lockoutExpired_allowsLoginAndClearsCounters() {
        UserPojo user = anActiveUser("acme-credc-2", "expired@acme.test");
        user.setFailedAttempts(10);
        user.setLockedUntil(Instant.now().minusSeconds(60));

        UserPojo found = credSvc.verifyLogin(user.getOrgId(), "expired@acme.test", pwd("s3cret!!"));

        assertEquals(0, found.getFailedAttempts());
        assertNull(found.getLockedUntil());
    }

    @Test
    public void verifyLogin_activeUserWithNoPasswordHash_throwsGenericFailure() {
        OrgPojo org = orgSvc.create("Acme", "acme-credc-3", "hr@acme.test", "IN");
        userSvc.createUser(org.getId(), "nopw@acme.test", new byte[0], UserRole.EMPLOYEE, UserStatus.ACTIVE);

        assertThrows(NotAuthException.class, //
            () -> credSvc.verifyLogin(org.getId(), "nopw@acme.test", pwd("s3cret!!")));
    }

    @Test
    public void verifyLogin_disabledUser_throwsGenericFailure() {
        UserPojo user = anActiveUser("acme-credc-4", "gone@acme.test");
        user.setStatus(UserStatus.DISABLED);

        assertThrows(NotAuthException.class, //
            () -> credSvc.verifyLogin(user.getOrgId(), "gone@acme.test", pwd("s3cret!!")));
    }

    @Test
    public void verifyLogin_wipesTheRawPasswordArray() {
        UserPojo user = anActiveUser("acme-credc-5", "wipe@acme.test");
        byte[] raw = pwd("s3cret!!");

        credSvc.verifyLogin(user.getOrgId(), "wipe@acme.test", raw);

        assertTrue(Arrays.equals(new byte[raw.length], raw));
    }

    // recFailed runs REQUIRES_NEW through the proxy, which cannot see this test's
    // uncommitted rows -- call the unproxied bean so it joins the test transaction.
    @Test
    public void recFailed_belowThreshold_incrementsWithoutLocking() {
        UserPojo user = anActiveUser("acme-credc-6", "count@acme.test");

        target().recFailed(user.getId());
        target().recFailed(user.getId());

        assertEquals(2, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
    }

    @Test
    public void recFailed_reachingMaxAttempts_locksTheAccount() {
        UserPojo user = anActiveUser("acme-credc-7", "lockme@acme.test");
        user.setFailedAttempts(9); // test.properties: app.login.max-failed-attempts=10

        target().recFailed(user.getId());

        assertEquals(10, user.getFailedAttempts());
        assertNotNull(user.getLockedUntil());
        assertTrue(user.getLockedUntil().isAfter(Instant.now().plusSeconds(14 * 60))); // lockout-minutes=15
    }

    @Test
    public void recFailed_pastMaxAttempts_staysLocked() {
        UserPojo user = anActiveUser("acme-credc-8", "stay@acme.test");
        user.setFailedAttempts(25);

        target().recFailed(user.getId());

        assertEquals(26, user.getFailedAttempts());
        assertNotNull(user.getLockedUntil());
    }

    @Test
    public void recFailed_unknownUser_isNoop() {
        assertDoesNotThrow(() -> target().recFailed(987654L));
    }

    @Test
    public void resetPassword_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> credSvc.resetPassword(987654L, pwd("s3cret!!")));
    }

    @Test
    public void resetPassword_activeUser_staysActiveAndRevokesSessions() {
        UserPojo user = anActiveUser("acme-credc-9", "reset@acme.test");

        credSvc.resetPassword(user.getId(), pwd("brand-new-pw"));

        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertNotNull(user.getTokensValidAfter());
        assertNotNull(credSvc.verifyLogin(user.getOrgId(), "reset@acme.test", pwd("brand-new-pw")));
    }

    @Test
    public void resetPassword_weakPassword_throwsBadRequestAndWipesInput() {
        UserPojo user = anActiveUser("acme-credc-10", "weakreset@acme.test");
        byte[] weak = pwd("short");

        assertThrows(BadRequestException.class, () -> credSvc.resetPassword(user.getId(), weak));
        assertTrue(Arrays.equals(new byte[weak.length], weak));
    }

    @Test
    public void setInitialPassword_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> credSvc.setInitialPassword(987654L, pwd("s3cret!!")));
    }

    @Test
    public void checkPwd_nullEmail_skipsTheContextCheck() {
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("whatever-pw"), null));
    }

    @Test
    public void checkPwd_shortEmailLocalPart_skipsTheContextCheck() {
        // local part "ab" is under 3 chars, so a password containing it is fine
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("xxabxxabxx"), "ab@acme.test"));
    }

    @Test
    public void checkPwd_localPartOfExactlyThreeChars_isEnforced_caseInsensitively() {
        assertThrows(BadRequestException.class, () -> credSvc.checkPwd(pwd("my-JOE-pass"), "joe@acme.test"));
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("my-ann-pass"), "joe@acme.test"));
    }

    @Test
    public void checkPwd_emailWithoutAtSign_usesWholeStringAsLocalPart() {
        assertThrows(BadRequestException.class, () -> credSvc.checkPwd(pwd("xxplainnamexx"), "plainname"));
    }

    @Test
    public void checkPwd_lengthBoundaries() {
        assertThrows(BadRequestException.class, () -> credSvc.checkPwd(pwd("abcdefg"), null)); // 7
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("abcdefgh"), null)); // 8
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("ab".repeat(36)), null)); // 72
        assertThrows(BadRequestException.class, () -> credSvc.checkPwd(pwd("ab".repeat(36) + "c"), null)); // 73
    }

    @Test
    public void checkPwd_oneDifferentByteIsNotRepetitive() {
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("aaaaaaab"), null)); // differs at the last byte
        assertDoesNotThrow(() -> credSvc.checkPwd(pwd("baaaaaaa"), null)); // differs at the first byte
        assertThrows(BadRequestException.class, () -> credSvc.checkPwd(pwd("aaaaaaaa"), null));
    }

    /* Private Helper Methods */

    private UserPojo anActiveUser(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        return userSvc.createUser(org.getId(), email, pwd("s3cret!!"), UserRole.EMPLOYEE);
    }

    private CredentialService target() {
        return AopTestUtils.getTargetObject(credSvc);
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
