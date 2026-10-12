package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.LoginData;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.model.enums.OrgStatus;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.EmailOutboxRepo;
import com.leaveapp.service.JwtService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

// Gap-filler for AuthFlowTest: every required-input arm of signup / login / forgot-password,
// the invite-link (set-password) step, the account states forgot-password stays silent
// about, and the two throttles' "cooldown already elapsed" arms.
public class AuthFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private AuthFlow authFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private EmailOutboxRepo outboxRepo;

    private Object target;
    private Object originalResetReqs;
    private Object originalCooldown;
    private Object originalResetCooldown;

    // AuthFlow is a singleton whose throttle settings live in private fields: remember
    // them so a test that tunes one cannot leak into the next.
    @BeforeEach
    public void rememberThrottleState() {
        target = AopTestUtils.getTargetObject(authFlow);
        originalResetReqs = ReflectionTestUtils.getField(target, "lastResetReqAt");
        originalCooldown = ReflectionTestUtils.getField(target, "cooldownSecs");
        originalResetCooldown = ReflectionTestUtils.getField(target, "resetCooldownSecs");
    }

    @AfterEach
    public void restoreThrottleState() {
        ReflectionTestUtils.setField(target, "lastResetReqAt", originalResetReqs);
        ReflectionTestUtils.setField(target, "cooldownSecs", originalCooldown);
        ReflectionTestUtils.setField(target, "resetCooldownSecs", originalResetCooldown);
    }

    /* Public Methods */

    @Test
    public void signup_nullOrgKey_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup(null, "a@acme.test", pwd("s3cret!!"), "US"));
    }

    @Test
    public void signup_blankOrgKey_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("   ", "a@acme.test", pwd("s3cret!!"), "US"));
    }

    @Test
    public void signup_nullEmail_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-authc-1", null, pwd("s3cret!!"), "US"));
    }

    @Test
    public void signup_blankEmail_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-authc-2", "  ", pwd("s3cret!!"), "US"));
    }

    @Test
    public void signup_emptyPassword_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-authc-3", "a@acme.test", new byte[0], "US"));
    }

    @Test
    public void signup_nullCountry_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-authc-4", "a@acme.test", pwd("s3cret!!"), null));
    }

    @Test
    public void signup_duplicateOrgKey_throwsConflict() {
        authFlow.signup("acme-authc-5", "first@acme.test", pwd("s3cret!!"), "US");

        assertThrows(ConflictException.class, //
            () -> authFlow.signup("acme-authc-5", "second@acme.test", pwd("s3cret!!"), "US"));
    }

    @Test
    public void signup_invalidOrgKeyFormat_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("Not Valid!", "a@acme.test", pwd("s3cret!!"), "US"));
    }

    @Test
    public void signup_weakPassword_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-authc-6", "a@acme.test", pwd("short"), "US"));
    }

    @Test
    public void login_nullOrgKey_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.login(null, "a@acme.test", pwd("s3cret!!")));
    }

    @Test
    public void login_blankOrgKey_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.login(" ", "a@acme.test", pwd("s3cret!!")));
    }

    @Test
    public void login_nullEmail_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.login("acme-authc-7", null, pwd("s3cret!!")));
    }

    @Test
    public void login_blankEmail_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.login("acme-authc-7", " ", pwd("s3cret!!")));
    }

    @Test
    public void login_emptyPassword_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.login("acme-authc-7", "a@acme.test", new byte[0]));
    }

    @Test
    public void login_orgKeyAndEmailAreTrimmed() {
        authFlow.signup("acme-authc-8", "trim@acme.test", pwd("s3cret!!"), "US");

        LoginData data = authFlow.login("  acme-authc-8 ", " trim@acme.test  ", pwd("s3cret!!"));

        assertNotNull(data.getToken());
    }

    @Test
    public void resendVerification_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> authFlow.resendVerification(987654L));
    }

    @Test
    public void resendVerification_cooldownAlreadyElapsed_sendsAgain() {
        authFlow.signup("acme-authc-9", "elapsed@acme.test", pwd("s3cret!!"), "US");
        Long userId = userId("acme-authc-9", "elapsed@acme.test");
        ReflectionTestUtils.setField(target, "cooldownSecs", 0L);

        authFlow.resendVerification(userId);
        authFlow.resendVerification(userId); // 0s cooldown: the first stamp is already in the past

        assertEquals(3, mailsFor(userId, EmailType.VERIFICATION)); // signup + the two resends
    }

    @Test
    public void forgotPassword_nullOrgKey_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.forgotPassword(null, "a@acme.test"));
    }

    @Test
    public void forgotPassword_nullEmail_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.forgotPassword("acme-authc-10", null));
    }

    @Test
    public void forgotPassword_blankEmail_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.forgotPassword("acme-authc-10", "   "));
    }

    @Test
    public void forgotPassword_activeUser_queuesOneResetMail() {
        authFlow.signup("acme-authc-11", "fp@acme.test", pwd("s3cret!!"), "US");
        Long userId = userId("acme-authc-11", "fp@acme.test");

        authFlow.forgotPassword(" acme-authc-11 ", " fp@acme.test ");

        assertEquals(1, mailsFor(userId, EmailType.PASSWORD_RESET));
    }

    @Test
    public void forgotPassword_secondCallInsideCooldown_queuesNoSecondMail() {
        authFlow.signup("acme-authc-12", "fp2@acme.test", pwd("s3cret!!"), "US");
        Long userId = userId("acme-authc-12", "fp2@acme.test");

        authFlow.forgotPassword("acme-authc-12", "fp2@acme.test");
        authFlow.forgotPassword("ACME-AUTHC-12", "FP2@acme.test"); // same throttle key, case-folded

        assertEquals(1, mailsFor(userId, EmailType.PASSWORD_RESET));
    }

    @Test
    public void forgotPassword_throttleEntryAlreadyExpired_queuesAnotherMail() {
        authFlow.signup("acme-authc-13", "fp3@acme.test", pwd("s3cret!!"), "US");
        Long userId = userId("acme-authc-13", "fp3@acme.test");
        StaleResetReqs stale = new StaleResetReqs();
        ReflectionTestUtils.setField(target, "lastResetReqAt", stale);

        authFlow.forgotPassword("acme-authc-13", "fp3@acme.test");

        assertEquals(1, mailsFor(userId, EmailType.PASSWORD_RESET)); // stale stamp: not throttled
        assertNotNull(stale.lastPut);
        assertTrue(stale.lastPut.isAfter(Instant.EPOCH)); // and the new request is stamped
    }

    @Test
    public void forgotPassword_unknownOrg_queuesNothing() {
        long before = outboxRepo.count();

        authFlow.forgotPassword("no-such-org-authc", "x@acme.test");

        assertEquals(before, outboxRepo.count());
    }

    @Test
    public void forgotPassword_suspendedOrg_queuesNothing() {
        authFlow.signup("acme-authc-14", "sus@acme.test", pwd("s3cret!!"), "US");
        Long userId = userId("acme-authc-14", "sus@acme.test");
        orgSvc.findByOrgKeyOrNull("acme-authc-14").setStatus(OrgStatus.SUSPENDED);

        authFlow.forgotPassword("acme-authc-14", "sus@acme.test");

        assertEquals(0, mailsFor(userId, EmailType.PASSWORD_RESET));
    }

    @Test
    public void forgotPassword_unknownEmailInKnownOrg_queuesNothing() {
        authFlow.signup("acme-authc-15", "known@acme.test", pwd("s3cret!!"), "US");
        long before = outboxRepo.count();

        authFlow.forgotPassword("acme-authc-15", "stranger@acme.test");

        assertEquals(before, outboxRepo.count());
    }

    @Test
    public void forgotPassword_disabledUser_queuesNothing() {
        authFlow.signup("acme-authc-16", "gone@acme.test", pwd("s3cret!!"), "US");
        UserPojo user = userSvc.findById(userId("acme-authc-16", "gone@acme.test"));
        user.setStatus(UserStatus.DISABLED);

        authFlow.forgotPassword("acme-authc-16", "gone@acme.test");

        assertEquals(0, mailsFor(user.getId(), EmailType.PASSWORD_RESET));
    }

    @Test
    public void setPassword_validInviteToken_setsPasswordAndStartsASession() {
        OrgPojo org = orgSvc.create("Acme", "acme-authc-17", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "inv@acme.test", new byte[0], UserRole.EMPLOYEE,
            UserStatus.INVITED);

        LoginData data = authFlow.setPassword(jwtSvc.issueSetPassword(user.getId()), pwd("s3cret!!"));

        assertNotNull(data.getToken());
        assertEquals(UserStatus.PENDING, user.getStatus());
        assertTrue(user.isEmailVerified());
        assertNotNull(authFlow.login("acme-authc-17", "inv@acme.test", pwd("s3cret!!")).getToken());
    }

    @Test
    public void setPassword_garbageToken_throwsBadRequestAndWipesPassword() {
        byte[] pw = pwd("s3cret!!");

        assertThrows(BadRequestException.class, () -> authFlow.setPassword("not-a-jwt", pw));
        assertTrue(Arrays.equals(new byte[pw.length], pw));
    }

    @Test
    public void setPassword_otherPurposeToken_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.setPassword(jwtSvc.issuePasswordReset(5L), pwd("s3cret!!")));
    }

    @Test
    public void setPassword_inviteLinkReplayedOnLiveAccount_throwsBadRequest() {
        authFlow.signup("acme-authc-18", "live@acme.test", pwd("s3cret!!"), "US");
        Long userId = userId("acme-authc-18", "live@acme.test"); // ACTIVE, not INVITED

        assertThrows(BadRequestException.class, //
            () -> authFlow.setPassword(jwtSvc.issueSetPassword(userId), pwd("an0ther-pw!")));
    }

    @Test
    public void setPassword_weakPassword_throwsBadRequestAndKeepsInviteOpen() {
        OrgPojo org = orgSvc.create("Acme", "acme-authc-19", "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), "weakinv@acme.test", new byte[0], UserRole.EMPLOYEE,
            UserStatus.INVITED);

        assertThrows(BadRequestException.class, //
            () -> authFlow.setPassword(jwtSvc.issueSetPassword(user.getId()), pwd("short")));
        assertEquals(UserStatus.INVITED, user.getStatus());
    }

    @Test
    public void resetPassword_validToken_changesPassword() {
        authFlow.signup("acme-authc-20", "rp@acme.test", pwd("origPass1"), "US");
        Long userId = userId("acme-authc-20", "rp@acme.test");

        authFlow.resetPassword(jwtSvc.issuePasswordReset(userId), pwd("newPass99"));

        assertNotNull(authFlow.login("acme-authc-20", "rp@acme.test", pwd("newPass99")).getToken());
    }

    @Test
    public void resetPassword_garbageToken_wipesPasswordArray() {
        byte[] pw = pwd("newPass99");

        assertThrows(BadRequestException.class, () -> authFlow.resetPassword("not-a-jwt", pw));
        assertTrue(Arrays.equals(new byte[pw.length], pw));
    }

    /* Private Helper Methods */

    private Long userId(String orgKey, String email) {
        return userSvc.findByOrgAndEmailOrNull(orgSvc.findByOrgKeyOrNull(orgKey).getId(), email).getId();
    }

    private long mailsFor(Long userId, EmailType type) {
        return outboxRepo.findAll().stream() //
            .filter(m -> userId.equals(m.getUserId()) && type == m.getType()).count();
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    /* Test Doubles */

    // A throttle map that never prunes and remembers every request as the epoch -- i.e. the
    // (org, email) pair was last seen long before the cooldown window started.
    private static class StaleResetReqs extends ConcurrentHashMap<String, Instant> {

        private Instant lastPut;

        @Override
        public Instant get(Object key) {
            return Instant.EPOCH;
        }

        @Override
        public Instant put(String key, Instant value) {
            lastPut = value;
            return super.put(key, value);
        }

        @Override
        public Set<Map.Entry<String, Instant>> entrySet() {
            return new HashSet<>();
        }
    }
}
