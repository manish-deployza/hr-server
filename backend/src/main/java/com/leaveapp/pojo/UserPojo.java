package com.leaveapp.pojo;

import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The identity / authentication / RBAC row. One per account, every role. The
// role-specific profile (employee, later admin/hr) is a separate 1:1 table keyed by
// this id -- see prompts/schema.md. Table is `account`, not `user`: `user` is a SQL
// keyword and would need quoting everywhere (jpa-persistence.md, keyword rule).
@Entity
@Table(
    name = "account",
    uniqueConstraints = @UniqueConstraint(name = "uk_account_org_email", columnNames = {"org_id", "email"})
)
@Getter
@Setter
@NoArgsConstructor
public class UserPojo extends AuditedPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "user_gen")
    @TableGenerator(
        name = "user_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "user_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // plain FK column, no @ManyToOne
    // -- matches every other FK in this codebase
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    // 189, not 190: 8 (org_id) + 189 x 4 = 764 bytes fits uk_account_org_email under
    // MySQL's 767-byte key limit; 190 would be 768.
    @Column(nullable = false, length = 189)
    private String email;

    // BCrypt output is always exactly 60 characters. Null until the employee sets
    // their own password via the invite link -- no admin-issued password, no forced reset
    // (code review: onboarding redesign).
    @Column(name = "password_hash", nullable = true, length = 60)
    private String passwordHash;

    // Set true by CredentialService.setInitialPassword (invite link) or AuthFlow.verifyEmail
    // (self-signup) -- clicking either emailed link is proof of inbox ownership. See
    // prompts/features/multi-tenancy.md.
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    // ORG_ADMIN, HR, EMPLOYEE -- the sole RBAC signal (JWT `role` claim ->
    // UserPrincipal.roles). A PENDING value is added when the HR-creates-employee
    // endpoint (which creates a row before the role is decided) is built.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role;

    // Account state only (INVITED/PENDING/ACTIVE/DISABLED); employment state is
    // EmpPojo.status. DISABLED blocks login (CredentialService.verifyLogin) and any
    // existing session (UserService.findIfSessionValid).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until", nullable = true)
    private Instant lockedUntil;

    // Session revocation: a JWT issued before this instant is rejected by
    // JwtAuthFilter (via JwtService/UserService.findIfSessionValid) even if not yet
    // expired. Bumped on logout, deactivation, role change, and password change.
    @Column(name = "tokens_valid_after", nullable = true)
    private Instant tokensValidAfter;
}
