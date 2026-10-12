package com.leaveapp.service;

// import flow: JwtService -> jjwt (external library, signs/parses the token)
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.security.UserPrincipal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    /* Variables */

    private static final String CLAIM_ORG = "orgId";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PURPOSE = "purpose";
    private static final String PURPOSE_EMAIL_VERIFY = "email-verify";
    private static final long EMAIL_VERIFY_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final String PURPOSE_PWD_RESET = "pwd-reset";
    private static final long PWD_RESET_TTL_MS = 30 * 60 * 1000L;
    private static final String PURPOSE_SET_PASSWORD = "set-password";
    // 7 days, not 24h like email-verify -- an HR-sent invite, unlike a self-triggered
    // verify link, has no guarantee the recipient checks their inbox same-day.
    private static final long SET_PASSWORD_TTL_MS = 7 * 24 * 60 * 60 * 1000L;

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiry-minutes}")
    private long expiryMin;

    // Session revocation check (logout / deactivate / role change / password change)
    // needs a DB hit -- see UserService.findIfSessionValid. Service -> Service is allowed;
    // this keeps JwtAuthFilter itself unchanged (still one parseOrNull call).
    @Autowired
    private UserService userSvc;

    /* Public Methods */

    // Returns the compact token string -- never a *Data shape; a Service must not
    // depend on *Data (the caller, AuthFlow, wraps this into LoginData).
    public String issue(UserPojo user) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expiryMin * 60_000);
        return Jwts.builder() //
            .subject(String.valueOf(user.getId())) //
            .claim(CLAIM_ORG, user.getOrgId()) //
            .claim(CLAIM_ROLE, user.getRole().name()) //
            .issuedAt(now) //
            .expiration(exp) //
            .signWith(key()) //
            .compact();
    }

    // null on any failure (bad signature, expired, malformed, or revoked) -- caller
    // treats it as unauthenticated, same null-on-miss convention used elsewhere.
    public UserPrincipal parseOrNull(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            UserPojo user = userSvc.findIfSessionValid(userId, claims.getIssuedAt().toInstant());
            if (user == null) {
                return null;
            }
            UserPrincipal p = new UserPrincipal();
            p.setUserId(userId);
            p.setOrgId(claims.get(CLAIM_ORG, Long.class));
            p.setRoles(List.of(UserRole.valueOf(claims.get(CLAIM_ROLE, String.class))));
            p.setOnboarding(UserStatus.PENDING == user.getStatus());
            return p;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public int expirySeconds() {
        return (int) (expiryMin * 60);
    }

    // Single-purpose token for the "verify your email" link -- 24h, carries only the
    // user id. Separate purpose claim so a leaked session token can't stand in for it
    // (and vice versa).
    public String issueEmailVerify(Long userId) {
        Date now = new Date();
        return Jwts.builder() //
            .subject(String.valueOf(userId)) //
            .claim(CLAIM_PURPOSE, PURPOSE_EMAIL_VERIFY) //
            .issuedAt(now) //
            .expiration(new Date(now.getTime() + EMAIL_VERIFY_TTL_MS)) //
            .signWith(key()) //
            .compact();
    }

    // user id from a valid email-verify token, or null (bad signature, expired,
    // wrong purpose) -- same null-on-failure convention as parseOrNull.
    public Long parseEmailVerify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
            if (!PURPOSE_EMAIL_VERIFY.equals(claims.get(CLAIM_PURPOSE, String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    // Single-purpose token for the "reset your password" link -- 30 min, carries only
    // the user id. Separate purpose claim so neither a session nor an email-verify token
    // can stand in for it (and vice versa).
    // ponytail: stateless + not single-use -- a reset link keeps working for its full
    // 30 minutes even after the password has changed. Tighten by rejecting a token whose
    // iat predates user.updated_at, or move to a hashed single-use token column, only if
    // that window is ever too wide.
    public String issuePasswordReset(Long userId) {
        Date now = new Date();
        return Jwts.builder() //
            .subject(String.valueOf(userId)) //
            .claim(CLAIM_PURPOSE, PURPOSE_PWD_RESET) //
            .issuedAt(now) //
            .expiration(new Date(now.getTime() + PWD_RESET_TTL_MS)) //
            .signWith(key()) //
            .compact();
    }

    // user id from a valid password-reset token, or null (bad signature, expired, wrong
    // purpose) -- same null-on-failure convention as parseOrNull.
    public Long parsePasswordReset(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
            if (!PURPOSE_PWD_RESET.equals(claims.get(CLAIM_PURPOSE, String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    // Single-purpose token for the employee-onboarding invite link -- 7 days, carries
    // only the user id. Issued once, at HrEmpFlow.persistRow, in place of an
    // email-verify token: this account has no password yet, so there's nothing to
    // "verify" until the employee sets one.
    public String issueSetPassword(Long userId) {
        Date now = new Date();
        return Jwts.builder() //
            .subject(String.valueOf(userId)) //
            .claim(CLAIM_PURPOSE, PURPOSE_SET_PASSWORD) //
            .issuedAt(now) //
            .expiration(new Date(now.getTime() + SET_PASSWORD_TTL_MS)) //
            .signWith(key()) //
            .compact();
    }

    // user id from a valid set-password token, or null (bad signature, expired, wrong
    // purpose) -- same null-on-failure convention as parseOrNull.
    public Long parseSetPassword(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
            if (!PURPOSE_SET_PASSWORD.equals(claims.get(CLAIM_PURPOSE, String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /* Private Helper Methods */

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
