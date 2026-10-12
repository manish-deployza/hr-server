package com.leaveapp.pojo;

import com.leaveapp.model.enums.LeaveStatus;

import java.math.BigDecimal;
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
// No index on (user_id/leave_type_id, status) -- no LeaveRequestRepo query filters on
// either pair. idx_leave_request_user backs LeaveRequestRepo.findByUserId;
// idx_leave_request_leave_type is the plain-FK-column index (jpa-persistence.md §12).
@Entity
@Table(
    name = "leave_request",
    indexes = {
        @Index(name = "idx_leave_request_user", columnList = "user_id"),
        @Index(name = "idx_leave_request_leave_type", columnList = "leave_type_id")
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

    // precision=4 (max 999.9 days) -- see LeaveTypePojo.maxCarryDays.
    @Column(name = "num_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal numDays;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LeaveStatus status;

    // true for disciplinary LOP created directly by HR, bypassing employee self-application
    @Column(name = "is_hr_imposed", nullable = false)
    private boolean hrImposed;

    @Column(name = "hr_notes", nullable = true, columnDefinition = "TEXT")
    private String hrNotes;
}
