package com.leaveapp.pojo;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Role-specific profile for a user who is an employee (EMPLOYEE, and also HR /
// ORG_ADMIN -- they take leave too). 1:1 with user: the PK *is* the user id, no
// separate generator. See prompts/schema.md.
@Entity
@Table(
    name = "employee",
    indexes = @Index(name = "ix_emp_mgr", columnList = "manager_id"),
    uniqueConstraints = @UniqueConstraint(name = "uk_employee_org_code", columnNames = {"org_id", "employee_code"})
)
@Getter
@Setter
@NoArgsConstructor
public class EmpPojo extends AuditedPojo {

    /* Variables */

    // No @GeneratedValue -- this is the user id, assigned by the caller before
    // save(). Spring Data then does select-then-insert (id is non-null so isNew() is
    // false); acceptable on the once-per-user creation path.
    // ponytail: implement Persistable<Long> with a transient isNew flag only if that
    // extra SELECT ever shows up hot.
    @Id
    @Column(name = "user_id")
    private Long userId;

    // plain FK column, no @ManyToOne -- matches every other FK in this codebase
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "employee_code", nullable = false, length = 64)
    private String employeeCode;

    // nullable -- not collected at signup (less friction); set later via settings.
    @Column(nullable = true, length = 128)
    private String name;

    @Column(name = "date_of_joining", nullable = false)
    private LocalDate dateOfJoining;

    @Column(name = "date_of_exit", nullable = true)
    private LocalDate dateOfExit;

    // "REGULAR", "INTERN", or any org-defined value -- plain string by design, not an
    // enum: new employee types are added as data, matched case-insensitively against
    // leave_type.applicable_employee_type, with no code deploy needed.
    @Column(name = "employee_type", nullable = false, length = 32)
    private String employeeType; //TODO Needs Customization

    @Column(nullable = true, length = 128)
    private String designation;

    @Column(nullable = true, length = 128)
    private String department;

    // self-referencing by convention: points at a user.id, no DB-level FK
    // (matches 02-database-schema.md's constraint policy for this project).
    @Column(name = "manager_id", nullable = true)
    private Long managerId;

    @Column(name = "basic_salary", nullable = true, precision = 12, scale = 2)
    private BigDecimal basicSalary;

    // Directory Step 3 (Org details) -- P0/P1, plain free text like department/
    // designation, no FK/enum infra to back a controlled taxonomy yet.
    @Column(name = "work_location", nullable = true, length = 128)
    private String workLocation;

    // Self-service profile photo -- see prompts/features/employee-auth-and-profile.changes.md.
    // No cloud storage lib access in this project (not part of the deployza org), so the
    // bytes live in the row rather than an external object store.
    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    private byte[] photo;

    @Column(name = "photo_content_type", nullable = true, length = 20)
    private String photoContentType;
}
