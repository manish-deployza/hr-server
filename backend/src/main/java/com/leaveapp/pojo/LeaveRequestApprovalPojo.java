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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// one row per approval step instance -- the audit trail of who acted at each step;
// see prompts/features/database-schema.md#leave_request_approval
// No updatedAt: the row's only mutation (LeaveFlow.stampStep sets action,
// approverUserId, comments and actedAt together) is already dated by actedAt.
@Entity
@Table(name = "leave_request_approval")
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequestApprovalPojo extends CreatedOnlyPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "lreq_appr_gen")
    @TableGenerator(
        name = "lreq_appr_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "leave_request_approval_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    @Column(name = "leave_request_id", nullable = false)
    private Long leaveRequestId;

    // mirrors leave_type_approval_step.step_order at time of application
    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", nullable = false, length = 32)
    private ApproverRole approverRole;

    // user id; null until someone actually acts on this step
    @Column(name = "approver_user_id", nullable = true)
    private Long approverUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApprovalAction action;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String comments;

    @Column(name = "acted_at", nullable = true)
    private Instant actedAt;
}
