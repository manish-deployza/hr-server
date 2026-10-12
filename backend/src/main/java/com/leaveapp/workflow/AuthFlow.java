package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB): org/user/employee creation and
// login verification, then JwtService issues the token (no DB access of its own).
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.model.data.LoginData;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.enums.OrgStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.JwtService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.PasswordResetMailer;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.CredentialService;
import com.leaveapp.service.UserService;
import com.leaveapp.service.VerificationMailer;

@Service
public class AuthFlow {

    /* Variables */

    private static final String LOGIN_FAIL = "Invalid credentials";

    // JDK's own ISO 3166-1 alpha-2 list -- no bundled country list/dependency needed.
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    // Per-user resend throttle. In-memory is enough: the app runs as a single WAR, and a
    // restart clearing a sub-minute cooldown is harmless.
    // ponytail: promote to a user.last_verification_email_at column only if multi-instance.
    private final Map<Long, Instant> lastResendAt = new ConcurrentHashMap<>();

    // Per-(orgKey:email) throttle on forgot-password, so the endpoint can't be used
    // to bomb a known address. Same in-memory rationale as lastResendAt; pruned on every
    // write so it stays bounded by request rate over one cooldown window.
    // ponytail: promote to a user column / shared cache only if multi-instance.
    private final Map<String, Instant> lastResetReqAt = new ConcurrentHashMap<>();

    @Value("${app.verify.resend-cooldown-seconds}")
    private long cooldownSecs;
    @Value("${app.reset.request-cooldown-seconds}")
    private long resetCooldownSecs;

    @Autowired
    private OrgService orgSvc;
    @Autowired
    private UserService userSvc;

    @Autowired
    private CredentialService credSvc;
    @Autowired
    private EmpService empSvc;
    @Autowired
    private JwtService jwtSvc;
    @Autowired
    private VerificationMailer verificationMailer;
    @Autowired
    private PasswordResetMailer passwordResetMailer;
    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    // Self-service org creation: no platform-admin gate. The caller sets their own
    // password directly (they're present, typing it in right now) -- no temp
    // password, no invite email, immediate session just like login. orgName/name are
    // deliberately not collected here (less friction). One transaction: org row +
    // identity (user) row + HR profile (employee) row.
    @Transactional(rollbackFor = Exception.class)
    public LoginData signup(String orgKey, String email, byte[] rawPwd, String country) {
        requireCredentials(orgKey, email, rawPwd);
        String isoCountry = requireCountry(country);
        // create() validates orgKey format/uniqueness (400/409); rawPwd zeroed
        // inside createUser regardless of outcome
        OrgPojo org = orgSvc.create(null, orgKey.trim(), email.trim(), isoCountry);
        UserPojo user = userSvc.createUser(org.getId(), email.trim(), rawPwd, UserRole.ORG_ADMIN);
        empSvc.createOrgAdminProfile(user.getId(), org.getId());
        subscriptionSvc.createFreeSubscription(org.getId());
        verificationMailer.send(user.getId(), user.getEmail());
        return toLoginData(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginData login(String orgKey, String email, byte[] rawPwd) {
        requireCredentials(orgKey, email, rawPwd);
        OrgPojo org = orgSvc.findByOrgKeyOrNull(orgKey.trim());
        // unknown org, suspended org, and (inside verifyLogin) unknown email/wrong
        // password/locked account all raise the same generic message -- never reveal
        // which one it was
        if (org == null || OrgStatus.SUSPENDED == org.getStatus()) {
            throw new NotAuthException(LOGIN_FAIL);
        }
        // verifyLogin zeroes rawPwd itself once it's done with it (see UserService)
        UserPojo user = credSvc.verifyLogin(org.getId(), email.trim(), rawPwd);
        // first login after submitting the onboarding form: employment ONBOARDED -> ACTIVE
        EmpPojo emp = empSvc.findByUserIdOrNull(user.getId());
        if (emp != null && EmpStatus.ONBOARDED == emp.getStatus()) {
            emp.setStatus(EmpStatus.ACTIVE);
        }
        return toLoginData(user);
    }

    // Follows the link mailed by HrEmpFlow. The signed token is the only proof
    // needed -- no session. A bad or expired token is a flat 400; we don't say which.
    @Transactional(rollbackFor = Exception.class)
    public void verifyEmail(String token) {
        Long userId = jwtSvc.parseEmailVerify(token);
        if (userId == null) {
            throw new BadRequestException("invalid or expired verification link");
        }
        userSvc.markVerified(userId);
    }

    // Re-send the verification link to the caller's own address. Only meaningful while
    // the profile is unverified; a verified account gets a 400 so the frontend can hide
    // the button. A second request inside app.verify.resend-cooldown-seconds is a 400 --
    // the cooldown is stamped on request, not on delivery, so it throttles rate not send
    // outcome. Not readOnly: verificationMailer.send now enqueues an email_outbox row.
    @Transactional(rollbackFor = Exception.class)
    public void resendVerification(Long userId) {
        UserPojo user = userSvc.findById(userId);
        if (user.isEmailVerified()) {
            throw new BadRequestException("email is already verified");
        }
        Instant now = Instant.now();
        Instant last = lastResendAt.get(userId);
        if (last != null && last.isAfter(now.minusSeconds(cooldownSecs))) {
            throw new BadRequestException("please wait before requesting another verification email");
        }
        lastResendAt.put(userId, now);
        verificationMailer.send(user.getId(), user.getEmail());
    }

    // Step 1 of self-service reset. Always returns void; the controller answers {} no
    // matter what, so a caller can't tell a registered address from an unknown one. Mail
    // is queued only for a real, usable account. A second request for the same
    // (orgKey, email) inside app.reset.request-cooldown-seconds is silently skipped
    // -- still a 200 {} -- so the endpoint can't be used to flood an inbox. Not readOnly:
    // passwordResetMailer.send now enqueues an email_outbox row.
    @Transactional(rollbackFor = Exception.class)
    public void forgotPassword(String orgKey, String email) {
        if (orgKey == null || orgKey.isBlank() || email == null || email.isBlank()) {
            throw new BadRequestException("organization orgKey and email are required");
        }
        // trim only, matching login/signup -- case-folding could resolve a different org
        String org = orgKey.trim();
        String addr = email.trim();
        String key = org.toLowerCase() + ":" + addr.toLowerCase();
        Instant now = Instant.now();
        pruneResetReqs(now);
        Instant last = lastResetReqAt.get(key);
        if (last != null && last.isAfter(now.minusSeconds(resetCooldownSecs))) {
            return;
        }
        lastResetReqAt.put(key, now);

        OrgPojo organization = orgSvc.findByOrgKeyOrNull(org);
        if (organization == null || OrgStatus.SUSPENDED == organization.getStatus()) {
            return;
        }
        UserPojo user = userSvc.findByOrgAndEmailOrNull(organization.getId(), addr);
        if (user == null || UserStatus.DISABLED == user.getStatus()) {
            return;
        }
        passwordResetMailer.send(user.getId(), user.getEmail());
    }

    // Step 2. The signed token from the emailed link is the only proof needed -- no
    // session. A bad, expired, or wrong-purpose token is a flat 400; we don't say which.
    // credSvc.resetPassword validates the new password and zeroes the array itself.
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String token, byte[] newPwd) {
        Long userId = jwtSvc.parsePasswordReset(token);
        if (userId == null) {
            Arrays.fill(newPwd, (byte) 0);
            throw new BadRequestException("invalid or expired reset link");
        }
        credSvc.resetPassword(userId, newPwd);
    }

    // Onboarding step 2: the employee's first password, from the invite link HR
    // creation mailed. Unlike resetPassword, this establishes a session -- same
    // posture as login, since the employee has never had a way in before now.
    @Transactional(rollbackFor = Exception.class)
    public LoginData setPassword(String token, byte[] newPwd) {
        Long userId = jwtSvc.parseSetPassword(token);
        if (userId == null) {
            Arrays.fill(newPwd, (byte) 0);
            throw new BadRequestException("invalid or expired invite link");
        }
        credSvc.setInitialPassword(userId, newPwd);
        return toLoginData(userSvc.findById(userId));
    }

    /* Private Helper Methods */

    private static void requireCredentials(String orgKey, String email, byte[] rawPwd) {
        if (orgKey == null || orgKey.isBlank()) {
            throw new BadRequestException("organization orgKey is required");
        }
        if (email == null || email.isBlank()) {
            throw new BadRequestException("email is required");
        }
        if (rawPwd.length == 0) {
            throw new BadRequestException("password is required");
        }
    }

    // Required at signup (frontend renders a country dropdown); ISO 3166-1 alpha-2 only,
    // matching AddressPojo.country's convention.
    private static String requireCountry(String country) {
        if (country == null || country.isBlank()) {
            throw new BadRequestException("country is required");
        }
        String upper = country.trim().toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(upper)) {
            throw new BadRequestException("country must be a valid ISO 3166-1 alpha-2 code");
        }
        return upper;
    }

    // JwtService.issue returns a bare token string; wrapping it into LoginData is this Flow's job.
    private LoginData toLoginData(UserPojo user) {
        LoginData data = new LoginData();
        data.setToken(jwtSvc.issue(user));
        return data;
    }

    // Drop entries older than the cooldown so lastResetReqAt stays bounded by how many
    // distinct (org, email) pairs asked for a reset within one window.
    private void pruneResetReqs(Instant now) {
        Instant cutoff = now.minusSeconds(resetCooldownSecs);
        lastResetReqAt.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }
}
