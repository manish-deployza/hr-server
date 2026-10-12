package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// CredentialService -> UserRepo -> DB (password hash, lockout counters, status moves on login/reset)
// CredentialService -> self (proxy re-entry, see the `self` field below, only for recFailed's REQUIRES_NEW)
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.UserRepo;

// Passwords and login: hashing, the NIST screen, verify-with-lockout, and the two
// mail-driven ways a password gets set (invite link, forgot-password link). Split out of
// UserService (identity lookups and lifecycle) to keep both under the size limit. Raw
// passwords are byte[] end to end and zeroed by whoever consumes them.
@Service
public class CredentialService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String LOGIN_FAIL = "Invalid credentials";
    // NIST SP 800-63B 5.1.1.2: SHALL be >= 8; SHALL allow >= 64. 72 = bcrypt's
    // byte limit -- past it bcrypt silently truncates, so reject rather than truncate.
    private static final int MIN_PWD_LEN = 8;
    private static final int MAX_PWD_BYTES = 72;

    @Autowired
    private UserRepo userRepo;

    // Self-injected so recFailed's REQUIRES_NEW actually opens a new transaction --
    // calling it as `this.recFailed(...)` would bypass Spring's proxy
    // (self-invocation) and just run in the caller's transaction, which is exactly
    // the bug this method exists to avoid.
    @Autowired
    @Lazy
    private CredentialService self;

    @Value("${app.login.max-failed-attempts}")
    private int maxFailed;

    @Value("${app.login.lockout-minutes}")
    private long lockoutMin;

    /* Public Methods */

    @Transactional(rollbackFor = Exception.class)
    public UserPojo verifyLogin(Long orgId, String email, byte[] rawPwd) {
        try {
            UserPojo user = userRepo.findByOrgIdAndEmail(orgId, email).orElse(null);
            // same LOGIN_FAIL message for "no such user" and "wrong password" -- never
            // reveal which one it was, that leaks whether an email is registered
            // INVITED = hasn't used the invite link yet; the link (set-password) is their
            // only way in, whether or not a password hash happens to exist.
            if (user == null || UserStatus.DISABLED == user.getStatus() || UserStatus.INVITED == user.getStatus()) {
                throw new NotAuthException(LOGIN_FAIL);
            }
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
                throw new NotAuthException(LOGIN_FAIL);
            }
            // null passwordHash = no password yet (BCryptPasswordEncoder.matches throws on
            // a null encoded password) -- same generic failure, no enumeration leak.
            if (user.getPasswordHash() == null || !matches(rawPwd, user.getPasswordHash())) {
                self.recFailed(user.getId()); // must persist even though this method is about to throw+rollback
                throw new NotAuthException(LOGIN_FAIL);
            }
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            log.info("login ok userId={}", user.getId());
            return user;
        } finally {
            Arrays.fill(rawPwd, (byte) 0); // wipe the plaintext password from memory either way
        }
    }

    // Self-service reset via an emailed token. Same policy screen + hash + wipe as the
    // invite path, and it also clears any lockout: completing the emailed link proves
    // inbox control, and the lockout only exists to slow brute force against the old
    // password, which no longer exists. An INVITED user who resets instead of using the
    // invite link lands in the same place the link would have put them: PENDING.
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, byte[] newPwd) {
        try {
            UserPojo user = find(userId);
            checkPwd(newPwd, user.getEmail());
            user.setPasswordHash(hashPwd(newPwd));
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            if (UserStatus.INVITED == user.getStatus()) {
                user.setStatus(UserStatus.PENDING);
                user.setEmailVerified(true);
            }
            user.setTokensValidAfter(Instant.now());
        } finally {
            Arrays.fill(newPwd, (byte) 0);
        }
    }

    // First-time password set via the onboarding invite link. Also marks emailVerified:
    // clicking the emailed link is itself proof of inbox ownership (see
    // UserPojo.emailVerified) -- there's no separate verify-email step for an invited
    // employee, since they had no password to log in and trigger one with.
    @Transactional(rollbackFor = Exception.class)
    public void setInitialPassword(Long userId, byte[] newPwd) {
        try {
            UserPojo user = find(userId);
            // the invite link is single-use: once the password is set the status moves on,
            // so a replayed link can't overwrite the password of a live account
            if (UserStatus.INVITED != user.getStatus()) {
                throw new BadRequestException("invalid or expired invite link");
            }
            checkPwd(newPwd, user.getEmail());
            user.setPasswordHash(hashPwd(newPwd));
            user.setStatus(UserStatus.PENDING);
            user.setEmailVerified(true);
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            user.setTokensValidAfter(Instant.now());
        } finally {
            Arrays.fill(newPwd, (byte) 0);
        }
    }

    // REQUIRES_NEW: must commit even when the caller's transaction (verifyLogin,
    // which throws right after calling this) rolls back -- otherwise every failed
    // attempt's counter increment is discarded along with the exception that
    // reports the failure, and the account can never actually lock.
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recFailed(Long userId) {
        UserPojo user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        int attempts = user.getFailedAttempts() + 1;
        user.setFailedAttempts(attempts);
        if (attempts >= maxFailed) {
            user.setLockedUntil(Instant.now().plusSeconds(lockoutMin * 60));
        }
    }

    // BCryptPasswordEncoder only takes CharSequence, so the byte[] has to become a
    // CharBuffer here at the last possible moment -- and get wiped right after
    public String hashPwd(byte[] rawPwd) {
        CharBuffer buf = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(rawPwd));
        try {
            return ENCODER.encode(buf);
        } finally {
            Arrays.fill(buf.array(), '\0');
        }
    }

    // NIST SP 800-63B password screening for user-chosen secrets. Deliberately NO
    // composition rules (no forced upper/digit/symbol mix) and NO expiry -- 800-63B
    // advises against both. Length + repetition + context check only; a
    // compromised-password blocklist (e.g. HaveIBeenPwned k-anonymity) is the upgrade path.
    public void checkPwd(byte[] rawPwd, String email) {
        if (rawPwd.length < MIN_PWD_LEN) {
            throw new BadRequestException("password must be at least " + MIN_PWD_LEN + " characters");
        }
        if (rawPwd.length > MAX_PWD_BYTES) {
            throw new BadRequestException("password must be at most " + MAX_PWD_BYTES + " bytes");
        }
        boolean allSame = true;
        for (byte b : rawPwd) {
            if (b != rawPwd[0]) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            throw new BadRequestException("password is too simple");
        }
        String local = email == null ? "" : email.toLowerCase().split("@", 2)[0];
        if (local.length() >= 3) {
            CharBuffer buf = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(rawPwd));
            try {
                if (buf.toString().toLowerCase().contains(local)) {
                    throw new BadRequestException("password must not contain your email address");
                }
            } finally {
                Arrays.fill(buf.array(), '\0');
            }
        }
    }

    /* Private Helper Methods */

    private UserPojo find(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new NotFoundException("user not found id=" + userId));
    }

    private static boolean matches(byte[] rawPwd, String stored) {
        CharBuffer buf = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(rawPwd));
        try {
            return ENCODER.matches(buf, stored); // constant-time compare, handled inside BCrypt
        } finally {
            Arrays.fill(buf.array(), '\0');
        }
    }
}
