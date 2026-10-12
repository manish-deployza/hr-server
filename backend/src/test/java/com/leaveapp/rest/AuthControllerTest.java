package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.LoginData;
import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.ForgotPwdForm;
import com.leaveapp.model.form.LoginForm;
import com.leaveapp.model.form.ResetPwdForm;
import com.leaveapp.model.form.SetPasswordForm;
import com.leaveapp.model.form.SignupForm;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.CaptchaService;
import com.leaveapp.service.JwtService;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.AuthFlow;
import com.leaveapp.workflow.EmpFlow;

// Plain unit test, same style as OnboardingControllerTest: hand-written recording doubles,
// no Spring context. Covers the cookie it writes/clears (every attribute, both cookie.secure
// branches), the captcha-before-flow ordering, the verify-email redirect branches, and the
// @PreAuthorize role gates on resend-verification and me.
public class AuthControllerTest {

    /* Variables */

    private final Calls calls = new Calls();
    private final RecordingAuthFlow authFlow = new RecordingAuthFlow(calls);
    private final RecordingEmpFlow empFlow = new RecordingEmpFlow();
    private final RecordingUserSvc userSvc = new RecordingUserSvc();
    private final RecordingCaptcha captcha = new RecordingCaptcha(calls);
    private final AuthController target = new AuthController();
    private final AuthController controller = SecurityProxyTestSupport.withPreAuthorize(target);
    private final MockHttpServletResponse res = new MockHttpServletResponse();

    {
        ReflectionTestUtils.setField(target, "authFlow", authFlow);
        ReflectionTestUtils.setField(target, "empFlow", empFlow);
        ReflectionTestUtils.setField(target, "userSvc", userSvc);
        ReflectionTestUtils.setField(target, "jwtSvc", new FixedJwt());
        ReflectionTestUtils.setField(target, "captchaSvc", captcha);
        ReflectionTestUtils.setField(target, "redirectBase", "https://app.test/login");
        ReflectionTestUtils.setField(target, "cookieName", "sid");
        ReflectionTestUtils.setField(target, "cookieSec", true);
        ReflectionTestUtils.setField(target, "cookieSameSite", "Strict");
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ---- signup / login ----

    @Test
    public void signup_verifiesCaptchaThenSetsSessionCookie() {
        SignupForm form = new SignupForm();
        form.setOrgKey("acme");
        form.setEmail("a@acme.test");
        form.setCountry("IN");
        form.setCaptchaToken("tok");

        assertTrue(controller.signup(form, res) != null);

        assertEquals(List.of("captcha:tok:signup", "signup:acme:a@acme.test:IN"), calls.log);
        assertCookie("jwt-1", 3600, true, "Strict");
    }

    @Test
    public void signup_captchaFailure_neverReachesFlowOrCookie() {
        captcha.fail = true;
        assertThrows(BadRequestException.class, () -> controller.signup(new SignupForm(), res));
        assertEquals(List.of("captcha:null:signup"), calls.log);
        assertNull(res.getHeader("Set-Cookie"));
    }

    @Test
    public void login_verifiesCaptchaThenSetsSessionCookie() {
        LoginForm form = new LoginForm();
        form.setOrgKey("acme");
        form.setEmail("a@acme.test");
        form.setCaptchaToken("tok");

        controller.login(form, res);

        assertEquals(List.of("captcha:tok:login", "login:acme:a@acme.test"), calls.log);
        assertCookie("jwt-1", 3600, true, "Strict");
    }

    @Test
    public void login_captchaFailure_neverReachesFlowOrCookie() {
        captcha.fail = true;
        assertThrows(BadRequestException.class, () -> controller.login(new LoginForm(), res));
        assertEquals(List.of("captcha:null:login"), calls.log);
        assertNull(res.getHeader("Set-Cookie"));
    }

    @Test
    public void cookie_omitsSecureFlagWhenDisabled_andHonoursSameSite() {
        ReflectionTestUtils.setField(target, "cookieSec", false);
        ReflectionTestUtils.setField(target, "cookieSameSite", "Lax");

        controller.login(new LoginForm(), res);

        assertCookie("jwt-1", 3600, false, "Lax");
    }

    // ---- verify-email ----

    @Test
    public void verifyEmail_validToken_redirectsVerified1() throws IOException {
        controller.verifyEmail("good", res);
        assertEquals("https://app.test/login?verified=1", res.getRedirectedUrl());
        assertEquals(List.of("verifyEmail:good"), calls.log);
    }

    @Test
    public void verifyEmail_badToken_redirectsVerified0() throws IOException {
        authFlow.verifyFailure = new BadRequestException("bad token");
        controller.verifyEmail("bad", res);
        assertEquals("https://app.test/login?verified=0", res.getRedirectedUrl());
    }

    @Test
    public void verifyEmail_userGone_redirectsVerified0() throws IOException {
        authFlow.verifyFailure = new NotFoundException("user gone");
        controller.verifyEmail("orphan", res);
        assertEquals("https://app.test/login?verified=0", res.getRedirectedUrl());
    }

    // ---- resend-verification ----

    @Test
    public void resendVerification_allowedRoles_passOwnUserId() {
        for (UserRole role : List.of(UserRole.ORG_ADMIN, UserRole.HR, UserRole.EMPLOYEE)) {
            calls.log.clear();
            authenticate(7L, "ROLE_" + role.name());
            controller.resendVerification();
            assertEquals(List.of("resend:7"), calls.log);
        }
    }

    @Test
    public void resendVerification_onboardingRoleAndAnonymous_forbidden() {
        authenticate(7L, "ROLE_ONBOARDING");
        assertThrows(AccessDeniedException.class, controller::resendVerification);
        SecurityContextHolder.clearContext();
        assertThrows(Exception.class, controller::resendVerification);
        assertTrue(calls.log.isEmpty());
    }

    // ---- forgot / reset / set password ----

    @Test
    public void forgotPassword_verifiesCaptchaWithForgotAction_thenCallsFlow() {
        ForgotPwdForm form = new ForgotPwdForm();
        form.setOrgKey("acme");
        form.setEmail("a@acme.test");
        form.setCaptchaToken("tok");

        controller.forgotPassword(form);

        assertEquals(List.of("captcha:tok:forgot_password", "forgot:acme:a@acme.test"), calls.log);
    }

    @Test
    public void forgotPassword_captchaFailure_neverReachesFlow() {
        captcha.fail = true;
        assertThrows(BadRequestException.class, () -> controller.forgotPassword(new ForgotPwdForm()));
        assertEquals(List.of("captcha:null:forgot_password"), calls.log);
    }

    @Test
    public void resetPassword_delegatesTokenAndPassword() {
        ResetPwdForm form = new ResetPwdForm();
        form.setToken("t");
        form.setNewPassword(new byte[] { 1, 2 });

        controller.resetPassword(form);

        assertEquals(List.of("reset:t:2"), calls.log);
    }

    @Test
    public void setPassword_delegatesAndSetsSessionCookie() {
        SetPasswordForm form = new SetPasswordForm();
        form.setToken("t");
        form.setNewPassword(new byte[] { 1 });

        controller.setPassword(form, res);

        assertEquals(List.of("setPwd:t:1"), calls.log);
        assertCookie("jwt-1", 3600, true, "Strict");
    }

    // ---- me ----

    @Test
    public void me_allowedRoles_returnProfileOfOwnUserId() {
        for (UserRole role : UserRole.values()) {
            authenticate(5L, "ROLE_" + role.name());
            assertSame(empFlow.returns, controller.me());
        }
        assertEquals(5L, empFlow.lastUserId);
    }

    @Test
    public void me_unknownAuthority_forbidden() {
        authenticate(5L, "ROLE_SOMETHING_ELSE");
        assertThrows(AccessDeniedException.class, controller::me);
    }

    @Test
    public void me_principalWithoutUserId_notAuthenticated() {
        authenticate(null, "ROLE_EMPLOYEE");
        assertThrows(NotAuthException.class, controller::me);
    }

    // ---- logout ----

    @Test
    public void logout_withSession_revokesAndClearsCookie() {
        authenticate(9L, "ROLE_EMPLOYEE");

        controller.logout(res);

        assertEquals(List.of(9L), userSvc.revoked);
        assertCookie("", 0, true, "Strict");
    }

    @Test
    public void logout_anonymous_stillClearsCookieWithoutRevoking() {
        controller.logout(res);

        assertTrue(userSvc.revoked.isEmpty());
        assertCookie("", 0, true, "Strict");
    }

    /* Private Helper Methods */

    // MockHttpServletResponse re-renders Set-Cookie (adds Expires, reorders attributes), so
    // assert on the attributes rather than the raw string.
    private void assertCookie(String value, int maxAge, boolean secure, String sameSite) {
        String h = res.getHeader("Set-Cookie");
        assertTrue(h.startsWith("sid=" + value + "; Path=/; Max-Age=" + maxAge), h);
        assertTrue(h.contains("; HttpOnly"), h);
        assertEquals(secure, h.contains("; Secure"), h);
        assertTrue(h.endsWith("SameSite=" + sameSite), h);
    }

    private static void authenticate(Long userId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(1L);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class Calls {
        private final List<String> log = new ArrayList<>();
        private int tokens;

        private LoginData login() {
            LoginData d = new LoginData();
            d.setToken("jwt-" + ++tokens);
            return d;
        }
    }

    private static final class RecordingAuthFlow extends AuthFlow {
        private final Calls calls;
        private RuntimeException verifyFailure;

        private RecordingAuthFlow(Calls calls) {
            this.calls = calls;
        }

        @Override
        public LoginData signup(String orgKey, String email, byte[] rawPwd, String country) {
            calls.log.add("signup:" + orgKey + ":" + email + ":" + country);
            return calls.login();
        }

        @Override
        public LoginData login(String orgKey, String email, byte[] rawPwd) {
            calls.log.add("login:" + orgKey + ":" + email);
            return calls.login();
        }

        @Override
        public void verifyEmail(String token) {
            calls.log.add("verifyEmail:" + token);
            if (verifyFailure != null) {
                throw verifyFailure;
            }
        }

        @Override
        public void resendVerification(Long userId) {
            calls.log.add("resend:" + userId);
        }

        @Override
        public void forgotPassword(String orgKey, String email) {
            calls.log.add("forgot:" + orgKey + ":" + email);
        }

        @Override
        public void resetPassword(String token, byte[] newPwd) {
            calls.log.add("reset:" + token + ":" + newPwd.length);
        }

        @Override
        public LoginData setPassword(String token, byte[] newPwd) {
            calls.log.add("setPwd:" + token + ":" + newPwd.length);
            return calls.login();
        }
    }

    private static final class RecordingEmpFlow extends EmpFlow {
        private final SessionData returns = new SessionData();
        private Long lastUserId;

        @Override
        public SessionData getProfile(Long userId) {
            lastUserId = userId;
            return returns;
        }
    }

    private static final class RecordingUserSvc extends UserService {
        private final List<Long> revoked = new ArrayList<>();

        @Override
        public void revokeSessions(Long userId) {
            revoked.add(userId);
        }
    }

    private static final class RecordingCaptcha extends CaptchaService {
        private final Calls calls;
        private boolean fail;

        private RecordingCaptcha(Calls calls) {
            this.calls = calls;
        }

        @Override
        public void verify(String token, String action) {
            calls.log.add("captcha:" + token + ":" + action);
            if (fail) {
                throw new BadRequestException("captcha failed");
            }
        }
    }

    private static final class FixedJwt extends JwtService {
        @Override
        public int expirySeconds() {
            return 3600;
        }
    }
}
