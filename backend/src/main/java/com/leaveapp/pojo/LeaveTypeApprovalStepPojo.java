package com.leaveapp.pojo;

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

// configurable approval chain per leave type -- see prompts/features/database-schema.md#leave_type_approval_step
@Entity
@Table(
    name = "leave_type_approval_step",
    uniqueConstraints = @UniqueConstraint(name = "uk_ltas_type_step", columnNames = {"leave_type_id", "step_order"})
)
@Getter
@Setter
@NoArgsConstructor
public class LeaveTypeApprovalStepPojo extends CreatedOnlyPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "leave_step_gen")
    @TableGenerator(
        name = "leave_step_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "leave_type_approval_step_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // plain FK column, no @ManyToOne -- see jpa-persistence.md
    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", nullable = false, length = 32)
    private ApproverRole approverRole;

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory;
}
