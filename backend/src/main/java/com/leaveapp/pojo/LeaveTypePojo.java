package com.leaveapp.pojo;

import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.ApproverRole;

import java.math.BigDecimal;

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

// per-org catalog, HR-managed -- see prompts/features/database-schema.md#leave_type
@Entity
@Table(
    name = "leave_type",
    uniqueConstraints = @UniqueConstraint(name = "uk_leave_type_org_code", columnNames = {"org_id", "code"})
)
@Getter
@Setter
@NoArgsConstructor
public class LeaveTypePojo extends AuditedPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "leave_type_gen")
    @TableGenerator(
        name = "leave_type_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "leave_type_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // plain FK column, no @ManyToOne -- matches every other FK in this codebase
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    // null = applies to every employee; otherwise "REGULAR", "INTERN", or any
    // org-defined employee type -- plain string, matched case-insensitively against
    // employee.employee_type; same reasoning as EmpPojo.employeeType. Java field
    // shortened from applicableEmployeeType; column name unchanged, no migration.
    @Column(name = "applicable_employee_type", nullable = true, length = 32)
    private String applicableType;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_strategy", nullable = false, length = 32)
    private AccrualStrategy accrualStrategy;

    // shape varies by accrualStrategy, e.g. {"cadence":"QUARTERLY","daysPerPeriod":4.75}
    @Column(name = "accrual_config", nullable = true, columnDefinition = "TEXT")
    private String accrualConfig;

    // Java field shortened from maxCarryForwardDays; column name unchanged, no migration.
    // precision=4 (max 999.9 days) -- plenty for a carry-forward cap; scale=1 keeps
    // half-day granularity.
    @Column(name = "max_carry_forward_days", nullable = true, precision = 4, scale = 1)
    private BigDecimal maxCarryDays;

    // Java field shortened from advanceNoticeDays; column name unchanged, no migration.
    @Column(name = "requires_advance_notice_days", nullable = true)
    private Integer noticeDays;

    // annual entitlement, prorated by joining month into the ledger. Null = no
    // accrual (LOP, Sabbatical). Drives the "N days/year" label the apply form shows.
    // precision=4 (max 999.9 days) -- see maxCarryDays.
    @Column(name = "days_per_year", nullable = true, precision = 4, scale = 1)
    private BigDecimal daysPerYear;

    // free-text policy blurb shown in the apply form
    @Column(name = "info_note", nullable = true, columnDefinition = "TEXT")
    private String infoNote;

    // who approves a request against this type; LeaveTypeService.create sets MANAGER
    // (the applicant's manager) -- the collapsed single-approver replacement for the
    // old leave_type_approval_step chain (code review: a multi-step chain was unused
    // complexity, one approver is enough).
    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", nullable = false, length = 32)
    private ApproverRole approverRole;

    @Column(name = "is_encashable", nullable = false)
    private boolean encashable;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
