package com.leaveapp.pojo;

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
    uniqueConstraints = @UniqueConstraint(name = "uk_leave_type_org_code", columnNames = {"organization_id", "code"})
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
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    // "ALL", "REGULAR", "INTERN", or any org-defined employee type -- plain string,
    // matched case-insensitively against employee.employee_type; same reasoning as
    // EmployeePojo.employeeType. Java field shortened from applicableEmployeeType;
    // column name unchanged, no migration.
    @Column(name = "applicable_employee_type", nullable = false, length = 32)
    private String applicableType;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_strategy", nullable = false, length = 32)
    private AccrualStrategy accrualStrategy;

    // shape varies by accrualStrategy, e.g. {"cadence":"QUARTERLY","daysPerPeriod":4.75}
    @Column(name = "accrual_config", nullable = true, columnDefinition = "TEXT")
    private String accrualConfig;

    // Java field shortened from maxCarryForwardDays; column name unchanged, no migration.
    @Column(name = "max_carry_forward_days", nullable = true, precision = 5, scale = 1)
    private BigDecimal maxCarryDays;

    // Java field shortened from advanceNoticeDays; column name unchanged, no migration.
    @Column(name = "requires_advance_notice_days", nullable = true)
    private Integer noticeDays;

    // annual entitlement, prorated by joining month into the ledger. Null = no
    // accrual (LOP, Sabbatical). Drives the "N days/year" label the apply form shows.
    @Column(name = "days_per_year", nullable = true, precision = 5, scale = 1)
    private BigDecimal daysPerYear;

    // free-text policy blurb shown in the apply form
    @Column(name = "info_note", nullable = true, columnDefinition = "TEXT")
    private String infoNote;

    @Column(name = "is_encashable", nullable = false)
    private boolean encashable;

    @Column(name = "is_situation_based", nullable = false)
    private boolean situationBased;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
