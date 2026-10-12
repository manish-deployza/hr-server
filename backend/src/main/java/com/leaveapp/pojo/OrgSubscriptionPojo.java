package com.leaveapp.pojo;

import java.time.Instant;

import com.leaveapp.model.enums.SubscriptionStatus;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 1:1 with organization -- one row per org, current effective plan. See
// prompts/features/billing-subscription.md. The two status indexes back
// SubscriptionScheduler's batch polls (findReconcilable, findGraceExpired).
@Entity
@Table(name = "org_subscription", uniqueConstraints = {
    @UniqueConstraint(name = "uk_org_subscription_org", columnNames = { "org_id" }),
    @UniqueConstraint(name = "uk_org_subscription_rzp_id", columnNames = { "razorpay_subscription_id" })
}, indexes = {
    @Index(name = "idx_org_subscription_status_updated", columnList = "status, updated_at"),
    @Index(name = "idx_org_subscription_status_grace", columnList = "status, grace_until")
})
@Getter
@Setter
@NoArgsConstructor
public class OrgSubscriptionPojo extends AuditedPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "org_subscription_gen")
    @TableGenerator(
        name = "org_subscription_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "org_subscription_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    // plain FK column -> plan.id; live-joined, not snapshotted -- see the feature doc's
    // Data Models note on why this differs from an invoice/financial snapshot.
    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubscriptionStatus status;

    // null while on FREE; unique when present (MySQL permits multiple NULLs under a
    // unique index, so every FREE org coexists under this constraint).
    @Column(name = "razorpay_subscription_id", nullable = true, length = 64)
    private String razorpaySubscriptionId;

    // plan the admin is paying for but Razorpay hasn't confirmed yet; planId (and so the
    // seat limit) keeps the old plan until activation promotes this one.
    @Column(name = "pending_plan_id", nullable = true)
    private Long pendingPlanId;

    @Column(name = "current_period_end", nullable = true)
    private Instant currentPeriodEnd;

    // set when a pending/halted webhook first lands; cleared on recovery or downgrade.
    @Column(name = "grace_until", nullable = true)
    private Instant graceUntil;
}
