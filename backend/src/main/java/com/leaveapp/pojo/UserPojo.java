package com.leaveapp.pojo;

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
// this id -- see prompts/schema.md. Table name is backtick-quoted: `user` is a
// keyword in H2 and a function name in MySQL, so it must always be a quoted
// identifier (Hibernate emits the dialect-correct quote from the backticks).
@Entity
@Table(
    name = "`user`",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_org_email", columnNames = {"organization_id", "email"})
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
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 190)
    private String email;

    // BCrypt output is always exactly 60 characters
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    // set true when the user completes a set-password link (clicking an emailed link
    // is itself proof of inbox ownership) -- see prompts/features/multi-tenancy.md.
    // Nothing sets this true yet: that flow isn't built.
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    // ORG_ADMIN, HR_ADMIN, EMPLOYEE -- the sole RBAC signal (JWT `role` claim ->
    // UserPrincipal.roles). A PENDING value is added when the HR-creates-employee
    // endpoint (which creates a row before the role is decided) is built.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role;

    // EXITED blocks login (EmployeeService/UserService verifyLogin) and any existing session.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    // separate from `status` -- soft-delete flag, never a hard DELETE.
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until", nullable = true)
    private Instant lockedUntil;
}
