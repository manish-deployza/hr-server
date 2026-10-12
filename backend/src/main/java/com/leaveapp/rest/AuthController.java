package com.leaveapp.rest;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.LoginData;
import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.form.ForgotPwdForm;
import com.leaveapp.model.form.LoginForm;
import com.leaveapp.model.form.ResetPwdForm;
import com.leaveapp.model.form.SetPasswordForm;
import com.leaveapp.model.form.SignupForm;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.service.CaptchaService;
import com.leaveapp.service.JwtService;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.AuthFlow;
import com.leaveapp.workflow.EmpFlow;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthFlow authFlow;

    @Autowired
    private EmpFlow empFlow;

    @Autowired
    private UserService userSvc;

    @Autowired
    private JwtService jwtSvc;

    @Autowired
    private CaptchaService captchaSvc;

    @Value("${app.verify.redirect-base}")
    private String redirectBase;

    // Cookie name/secure flag come from app.properties, not hardcoded, so
    // prod (HTTPS) and local dev (HTTP) can differ.
    @Value("${app.cookie.name}")
    private String cookieName;

    @Value("${app.cookie.secure}")
    private boolean cookieSec;

    // jakarta.servlet.http.Cookie has no SameSite setter (not part of the servlet spec),
    // hence the hand-built Set-Cookie header below instead of res.addCookie. This is the
    // app's CSRF control for the cookie-borne session token -- see SecurityConfig's csrf
    // disable() -- so it is never sent as "None".
    @Value("${app.cookie.same-site:Strict}")
    private String cookieSameSite;

    /* Public Methods */

    @PostMapping("/signup")
    public AckData signup(@RequestBody SignupForm form, HttpServletResponse res) {
        log.info("POST /api/auth/signup orgKey={}", form.getOrgKey());
        captchaSvc.verify(form.getCaptchaToken(), "signup");
        LoginData data = authFlow.signup(form.getOrgKey(), form.getEmail(), form.getPassword(), form.getCountry());
        writeSessionCookie(res, data.getToken(), jwtSvc.expirySeconds());
        log.info("POST /api/auth/signup completed");
        return new AckData();
    }

    @PostMapping("/login")
    public AckData login(@RequestBody LoginForm form, HttpServletResponse res) {
        log.info("POST /api/auth/login orgKey={}", form.getOrgKey());
        captchaSvc.verify(form.getCaptchaToken(), "login");
        LoginData data = authFlow.login(form.getOrgKey(), form.getEmail(), form.getPassword());
        writeSessionCookie(res, data.getToken(), jwtSvc.expirySeconds());
        log.info("POST /api/auth/login completed");
        return new AckData();
    }

    // Public: the signed token in the query string is the credential. The link is
    // clicked straight from a mail client, so this checks the token then 302s the
    // browser to the frontend with ?verified=1 (ok) or ?verified=0 (bad/expired).
    @GetMapping("/verify-email")
    public void verifyEmail(@RequestParam("token") String token, HttpServletResponse res) throws IOException {
        log.info("GET /api/auth/verify-email");
        String verified = "1";
        try {
            authFlow.verifyEmail(token);
        } catch (BadRequestException | NotFoundException e) {
            // bad/expired token (BadRequestException) or a token whose user no longer
            // exists (NotFoundException, e.g. after a DB reset) -> same graceful
            // redirect, never a raw error to the browser.
            log.warn("verify-email rejected: {}", e.getMessage());
            verified = "0";
        }
        res.sendRedirect(redirectBase + "?verified=" + verified);
    }

    // Re-send the verification link to the logged-in user's own address. Used from the
    // "your email isn't verified" banner. 400 if already verified.
    @PostMapping("/resend-verification")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR', 'EMPLOYEE')")
    public AckData resendVerification() {
        Long userId = requireUserId();
        log.info("POST /api/auth/resend-verification userId={}", userId);
        authFlow.resendVerification(userId);
        return new AckData();
    }

    // Step 1 of self-service reset. Public: no identity needed. Always {} 200, even for
    // an unknown org/email, a throttled repeat, or an SMTP failure -- never signal
    // whether the address is registered.
    @PostMapping("/forgot-password")
    public AckData forgotPassword(@RequestBody ForgotPwdForm form) {
        log.info("POST /api/auth/forgot-password orgKey={}", form.getOrgKey());
        captchaSvc.verify(form.getCaptchaToken(), "forgot_password");
        authFlow.forgotPassword(form.getOrgKey(), form.getEmail());
        log.info("POST /api/auth/forgot-password completed");
        return new AckData();
    }

    // Step 2. Public: the signed token in the body is the credential. 400 on a
    // bad/expired token or a password that fails the policy screen.
    @PostMapping("/reset-password")
    public AckData resetPassword(@RequestBody ResetPwdForm form) {
        log.info("POST /api/auth/reset-password");
        authFlow.resetPassword(form.getToken(), form.getNewPassword());
        log.info("POST /api/auth/reset-password completed");
        return new AckData();
    }

    // Onboarding step 2. Public: the signed invite token in the body is the
    // credential. 400 on a bad/expired token or a password that fails the policy
    // screen; on success establishes a session, same as login/signup.
    @PostMapping("/set-password")
    public AckData setPassword(@RequestBody SetPasswordForm form, HttpServletResponse res) {
        log.info("POST /api/auth/set-password");
        LoginData data = authFlow.setPassword(form.getToken(), form.getNewPassword());
        writeSessionCookie(res, data.getToken(), jwtSvc.expirySeconds());
        log.info("POST /api/auth/set-password completed");
        return new AckData();
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR', 'EMPLOYEE', 'ONBOARDING')")
    public SessionData me() {
        Long userId = requireUserId();
        log.info("GET /api/auth/me userId={}", userId);
        return empFlow.getProfile(userId);
    }

    // Revokes the session server-side (see UserService.findIfSessionValid) so the cookie's
    // token stops working immediately, not just once it expires, then clears it.
    @PostMapping("/logout")
    public AckData logout(HttpServletResponse res) {
        log.info("POST /api/auth/logout");
        Long userId = userIdOrNull();
        if (userId != null) {
            userSvc.revokeSessions(userId);
        }
        clearSessionCookie(res);
        return new AckData();
    }

    /* Private Helper Methods */

    private void writeSessionCookie(HttpServletResponse res, String token, int maxAgeSec) {
        res.addHeader("Set-Cookie", buildCookieHeader(token, maxAgeSec));
    }

    // maxAge 0 tells the browser to delete the cookie immediately -- used on logout.
    private void clearSessionCookie(HttpServletResponse res) {
        res.addHeader("Set-Cookie", buildCookieHeader("", 0));
    }

    // token is a compact JWT (base64url + '.' separators only), so it needs no cookie-value
    // encoding here.
    private String buildCookieHeader(String value, int maxAgeSec) {
        StringBuilder header = new StringBuilder();
        header.append(cookieName).append('=').append(value);
        header.append("; Path=/");
        header.append("; Max-Age=").append(maxAgeSec);
        header.append("; HttpOnly"); // not readable from JS -- mitigates XSS token theft
        if (cookieSec) {
            header.append("; Secure");
        }
        header.append("; SameSite=").append(cookieSameSite);
        return header.toString();
    }
}
