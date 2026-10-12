package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// UserService -> UserRepo -> DB (find/create user, verify + hash passwords)
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.repo.UserRepo;

// Identity / authentication concerns only. The HR profile (name, code, joining date,
// manager, salary) lives on EmpService/EmpPojo.
@Service
public class UserService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private CredentialService credSvc;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public UserPojo findById(Long id) {
        return userRepo.findById(id) //
            .orElseThrow(() -> new NotFoundException("user not found id=" + id));
    }

    // (org, email) is the tenant-scoped identity key -- two orgs may reuse an email.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public boolean existsInOrgByEmail(Long orgId, String email) {
        return userRepo.existsByOrgIdAndEmail(orgId, email);
    }

    // Non-throwing lookup by the tenant-scoped identity key -- the forgot-password
    // flow needs it to stay silent about whether the address is registered.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public UserPojo findByOrgAndEmailOrNull(Long orgId, String email) {
        return userRepo.findByOrgIdAndEmail(orgId, email).orElse(null);
    }

    // used to confirm a managerId points at a user in the caller's own org.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public boolean existsInOrg(Long orgId, Long userId) {
        return userRepo.existsByIdAndOrgId(userId, orgId);
    }

    // every identity row in one org -- HR employee list. ponytail: unpaged; add a
    // Pageable overload if an org's headcount ever makes this list too big to ship whole.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<UserPojo> findByOrg(Long orgId) {
        return userRepo.findByOrgId(orgId);
    }

    // Batch-fetch for just the given ids (e.g. one already-paginated page's worth) --
    // matches EmpSensitiveService.findByUserIds's id-> row map convention.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Map<Long, UserPojo> findByIds(Collection<Long> ids) {
        return userRepo.findAllById(ids).stream().collect(Collectors.toMap(UserPojo::getId, Function.identity()));
    }

    // Notification fan-out target for "HR" events -- ORG_ADMIN is included because
    // every HR-scoped endpoint already treats the two roles as equivalent
    // (SecurityConfig's hasAnyRole("ORG_ADMIN", "HR") on /api/hr/**).
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<UserPojo> findHrAndAdminUsersByOrg(Long orgId) {
        return userRepo.findByOrgIdAndRoleIn(orgId, List.of(UserRole.HR, UserRole.ORG_ADMIN));
    }

    // BillingMailer's recipient list -- billing/plan changes are ORG_ADMIN-only,
    // unlike the HR-inclusive list above.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<UserPojo> findOrgAdminsByOrg(Long orgId) {
        return userRepo.findByOrgIdAndRoleIn(orgId, List.of(UserRole.ORG_ADMIN));
    }

    // SubscriptionService's seat-cap math -- how many non-DISABLED accounts the org
    // currently has, compared against the current plan's seatLimit.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public long countActiveByOrgId(Long orgId) {
        return userRepo.countActiveByOrgId(orgId);
    }

    // Session-validity check for JwtAuthFilter (via JwtService): a token issued
    // before tokensValidAfter is stale (logout/deactivation/role/password change),
    // and an exited or deactivated account has nothing left to authenticate.
    //
    // tokensValidAfter is truncated to whole seconds before comparing: a JWT `iat` is
    // always whole seconds (spec numeric-date), so without this a revoke that lands after
    // an `iat`'s truncated second but within that same wall-clock second (e.g. revoke at
    // 12:00:00.400, fresh login at 12:00:00.600 -> iat truncates to 12:00:00.000) makes the
    // brand-new token fail its own revocation check.
    //
    // Returns the user, or null when the session is no longer valid -- JwtService needs
    // the status to scope a PENDING user's session to onboarding.
    public UserPojo findIfSessionValid(Long userId, Instant issuedAt) {
        UserPojo user = userRepo.findById(userId).orElse(null);
        if (user == null || UserStatus.DISABLED == user.getStatus()) {
            return null;
        }
        Instant validAfter = user.getTokensValidAfter();
        boolean valid = validAfter == null || !issuedAt.isBefore(validAfter.truncatedTo(ChronoUnit.SECONDS));
        return valid ? user : null;
    }

    // Called wherever an existing session must stop working immediately: logout
    // (AuthController), deactivation and role change (HrEmpFlow).
    @Transactional(rollbackFor = Exception.class)
    public void revokeSessions(Long userId) {
        findById(userId).setTokensValidAfter(Instant.now());
    }

    // The onboarding form was submitted: account PENDING -> ACTIVE (the employment side
    // moves to ONBOARDED in OnboardingFlow). Revokes the onboarding-only session too, so
    // the user is sent to the login form and comes back as a full user.
    @Transactional(rollbackFor = Exception.class)
    public void activate(Long userId) {
        UserPojo user = findById(userId);
        if (UserStatus.PENDING != user.getStatus()) {
            throw new BadRequestException("onboarding is not in progress");
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setTokensValidAfter(Instant.now());
    }

    // Self-signup org creator, or any caller with no onboarding gate to worry about --
    // ACTIVE from creation; see the 5-arg overload for the invited-employee case.
    @Transactional(rollbackFor = Exception.class)
    public UserPojo createUser(Long orgId, String email, byte[] rawPwd, UserRole role) {
        return createUser(orgId, email, rawPwd, role, UserStatus.ACTIVE);
    }

    // Self-signup org creator sets their own password directly (no invite link --
    // they are present and typing it in right now). An invited
    // employee instead gets an empty rawPwd here -- passwordHash stays null until
    // they set their own via the invite link. The
    // matching employee profile row is created separately by EmpService.
    @Transactional(rollbackFor = Exception.class)
    public UserPojo createUser(Long orgId, String email, byte[] rawPwd, UserRole role,
        UserStatus status) {
        try {
            boolean hasPassword = rawPwd.length > 0;
            if (hasPassword) {
                credSvc.checkPwd(rawPwd, email);
            }
            Instant now = Instant.now();
            UserPojo user = new UserPojo();
            user.setOrgId(orgId);
            user.setEmail(email);
            user.setEmailVerified(false);
            user.setPasswordHash(hasPassword ? credSvc.hashPwd(rawPwd) : null);
            user.setRole(role);
            user.setStatus(status);
            user.setFailedAttempts(0);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            UserPojo saved = userRepo.save(user);
            log.info("user created userId={} orgId={} role={}", saved.getId(), orgId, role);
            return saved;
        } finally {
            Arrays.fill(rawPwd, (byte) 0);
        }
    }

    // Marks the email confirmed once the user follows the verification link.
    // Idempotent -- a second click on the same link is a no-op, not an error.
    @Transactional(rollbackFor = Exception.class)
    public void markVerified(Long userId) {
        UserPojo user = findById(userId);
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            log.info("email verified userId={}", userId);
        }
    }
}
