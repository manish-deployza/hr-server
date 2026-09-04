package com.leaveapp.pojo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// see prompts/features/database-schema.md#leave_request and workflow-state-machines.md for `status`
// No index on (user_id/leave_type_id, status) -- LeaveRequestRepo had no caller for
// either query, so both composite indexes were pure write cost (see V33). ix_lreq_user
// backs the real LeaveRequestRepo.findByUserId query; ix_lreq_leave_type has no query
// behind it either -- it only exists because MySQL requires some index on an FK column
// (fk_lreq_leave_type) and won't leave one with none.
@Entity
@Table(
    name = "leave_request",
    indexes = {
        @Index(name = "ix_lreq_user", columnList = "user_id"),
        @Index(name = "ix_lreq_leave_type", columnList = "leave_type_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequestPojo extends AuditedPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "leave_request_gen")
    @TableGenerator(
        name = "leave_request_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "leave_request_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // the user this request belongs to
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "num_days", nullable = false, precision = 5, scale = 1)
    private BigDecimal numDays;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String reason;

    // maternity sub-case only -- null for every other leave type
    @Column(name = "situation_type", nullable = true, length = 32)
    private SituationType situationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LeaveStatus status;

    // Java field shortened from currentStepOrder; column name unchanged, no migration.
    @Column(name = "current_step_order", nullable = false)
    private int stepOrder;

    // true for disciplinary LOP created directly by HR, bypassing employee self-application
    @Column(name = "is_hr_imposed", nullable = false)
    private boolean hrImposed;

    @Column(name = "hr_notes", nullable = true, columnDefinition = "TEXT")
    private String hrNotes;

    // self-FK: links a miscarriage-triggered request back to the original maternity request
    @Column(name = "parent_request_id", nullable = true)
    private Long parentRequestId;

    // set unconditionally at apply time (LeaveFlow.apply) -- never null in practice
    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "cancelled_at", nullable = true)
    private Instant cancelledAt;
}
