package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.model.data.LoginData;
import com.leaveapp.model.enums.OrgStatus;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.OrgRepo;
import com.leaveapp.repo.OrgTestData;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.service.JwtService;

public class AuthFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private AuthFlow authFlow;

    @Autowired
    private OrgRepo orgRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private JwtService jwtSvc;

    /* Public Methods */

    @Test
    public void signup_createsOrgAndAdmin_returnsToken() {
        LoginData data = authFlow.signup("acme-flow-1", "jane@acme.test", pwd("s3cret!!"), "US");
        assertNotNull(data.getToken());
    }

    @Test
    public void signup_missingCountry_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-flow-3", "nocountry@acme.test", pwd("s3cret!!"), " "));
    }

    @Test
    public void signup_invalidCountry_throwsBadRequest() {
        assertThrows(BadRequestException.class, //
            () -> authFlow.signup("acme-flow-4", "badcountry@acme.test", pwd("s3cret!!"), "ZZ"));
    }

    @Test
    public void signup_lowercasesCountry_storedUppercase() {
        authFlow.signup("acme-flow-16", "lowercountry@acme.test", pwd("s3cret!!"), "in");
        assertEquals("IN", orgRepo.findByOrgKey("acme-flow-16").orElseThrow().getCountry());
    }

    @Test
    public void signupThenLogin_succeeds() {
        authFlow.signup("acme-flow-2", "jane2@acme.test", pwd("s3cret!!"), "US");
        LoginData data = authFlow.login("acme-flow-2", "jane2@acme.test", pwd("s3cret!!"));
        assertNotNull(data.getToken());
    }

    @Test
    public void login_unknownOrgUsername_throwsGenericFailure() {
        assertThrows(NotAuthException.class, //
            () -> authFlow.login("no-such-org", "anyone@test.com", pwd("whatever")));
    }

    @Test
    public void login_suspendedOrg_throwsGenericFailure() {
        var org = OrgTestData.anOrg("acme-flow-3");
        org.setStatus(OrgStatus.SUSPENDED);
        orgRepo.saveAndFlush(org);

        assertThrows(NotAuthException.class, //
            () -> authFlow.login("acme-flow-3", "anyone@test.com", pwd("whatever")));
    }

    @Test
    public void verifyEmail_validToken_setsEmailVerified() {
        authFlow.signup("acme-flow-5", "verify@acme.test", pwd("s3cret!!"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "verify@acme.test".equals(u.getEmail())).findFirst().orElseThrow();
        assertFalse(user.isEmailVerified());

        authFlow.verifyEmail(jwtSvc.issueEmailVerify(user.getId()));

        assertTrue(userRepo.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    public void resendVerification_whenUnverified_succeeds() {
        authFlow.signup("acme-flow-7", "resend@acme.test", pwd("s3cret!!"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "resend@acme.test".equals(u.getEmail())).findFirst().orElseThrow();

        authFlow.resendVerification(user.getId()); // mail send is swallowed in tests

        assertFalse(userRepo.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    public void resendVerification_whenAlreadyVerified_throwsBadRequest() {
        authFlow.signup("acme-flow-8", "done@acme.test", pwd("s3cret!!"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "done@acme.test".equals(u.getEmail())).findFirst().orElseThrow();
        authFlow.verifyEmail(jwtSvc.issueEmailVerify(user.getId()));

        assertThrows(BadRequestException.class, () -> authFlow.resendVerification(user.getId()));
    }

    @Test
    public void resendVerification_secondCallWithinCooldown_throwsBadRequest() {
        authFlow.signup("acme-flow-9", "cooldown@acme.test", pwd("s3cret!!"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "cooldown@acme.test".equals(u.getEmail())).findFirst().orElseThrow();

        authFlow.resendVerification(user.getId());

        // test.properties sets a 60s cooldown -- an immediate second call is rejected
        assertThrows(BadRequestException.class, () -> authFlow.resendVerification(user.getId()));
    }

    @Test
    public void verifyEmail_garbageToken_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.verifyEmail("not-a-jwt"));
    }

    @Test
    public void verifyEmail_sessionTokenRejected() {
        LoginData session = authFlow.signup("acme-flow-6", "wrongtok@acme.test", pwd("s3cret!!"), "US");
        // a normal login token has no email-verify purpose claim
        assertThrows(BadRequestException.class, () -> authFlow.verifyEmail(session.getToken()));
    }

    @Test
    public void forgotThenReset_newPasswordWorks_oldFails() {
        authFlow.signup("acme-flow-10", "fyp@acme.test", pwd("origPass1"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "fyp@acme.test".equals(u.getEmail())).findFirst().orElseThrow();

        authFlow.forgotPassword("acme-flow-10", "fyp@acme.test"); // mail swallowed in tests
        authFlow.resetPassword(jwtSvc.issuePasswordReset(user.getId()), pwd("newPass99"));

        assertNotNull(authFlow.login("acme-flow-10", "fyp@acme.test", pwd("newPass99")).getToken());
        assertThrows(NotAuthException.class,
            () -> authFlow.login("acme-flow-10", "fyp@acme.test", pwd("origPass1")));
    }

    @Test
    public void forgotPassword_unknownOrgAndEmail_isSilentNoOp() {
        authFlow.forgotPassword("no-such-org", "nobody@nowhere.test"); // must not throw
    }

    @Test
    public void forgotPassword_neverAltersThePassword() {
        authFlow.signup("acme-flow-15", "fp15@acme.test", pwd("origPass1"), "US");

        // whatever it resolves to, forgot-password only ever *sends a link* -- it never
        // touches the stored password. A wrong org, the right org, a throttled repeat:
        // the original credentials must still work afterwards.
        authFlow.forgotPassword("no-such-org-15", "fp15@acme.test");
        authFlow.forgotPassword("acme-flow-15", "fp15@acme.test");

        assertNotNull(authFlow.login("acme-flow-15", "fp15@acme.test", pwd("origPass1")).getToken());
    }

    @Test
    public void forgotPassword_blankInput_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> authFlow.forgotPassword("  ", "a@b.test"));
    }

    @Test
    public void forgotPassword_secondCallWithinCooldown_isSilentNoOp() {
        authFlow.signup("acme-flow-12", "cd2@acme.test", pwd("origPass1"), "US");
        authFlow.forgotPassword("acme-flow-12", "cd2@acme.test");
        authFlow.forgotPassword("acme-flow-12", "cd2@acme.test"); // no throw, just skipped
    }

    @Test
    public void resetPassword_garbageToken_throwsBadRequest() {
        assertThrows(BadRequestException.class,
            () -> authFlow.resetPassword("not-a-jwt", pwd("newPass99")));
    }

    @Test
    public void resetPassword_sessionTokenRejected() {
        LoginData session = authFlow.signup("acme-flow-14", "stok@acme.test", pwd("origPass1"), "US");
        assertThrows(BadRequestException.class,
            () -> authFlow.resetPassword(session.getToken(), pwd("newPass99")));
    }

    @Test
    public void resetPassword_weakPassword_throwsBadRequest() {
        authFlow.signup("acme-flow-11", "weak@acme.test", pwd("origPass1"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "weak@acme.test".equals(u.getEmail())).findFirst().orElseThrow();
        assertThrows(BadRequestException.class,
            () -> authFlow.resetPassword(jwtSvc.issuePasswordReset(user.getId()), pwd("short")));
    }

    @Test
    public void resetPassword_clearsLockout() {
        authFlow.signup("acme-flow-13", "lock@acme.test", pwd("origPass1"), "US");
        UserPojo user = userRepo.findAll().stream()
            .filter(u -> "lock@acme.test".equals(u.getEmail())).findFirst().orElseThrow();
        user.setFailedAttempts(10);
        user.setLockedUntil(Instant.now().plusSeconds(3600));
        userRepo.saveAndFlush(user);

        authFlow.resetPassword(jwtSvc.issuePasswordReset(user.getId()), pwd("newPass99"));

        UserPojo after = userRepo.findById(user.getId()).orElseThrow();
        assertEquals(0, after.getFailedAttempts());
        assertNull(after.getLockedUntil());
    }

    /* Private Helper Methods */

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
