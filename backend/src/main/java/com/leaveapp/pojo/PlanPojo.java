package com.leaveapp.pojo;

import java.time.Instant;

import com.leaveapp.model.enums.BillingPeriod;
import com.leaveapp.model.enums.PlanCode;

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

// Global subscription-plan catalog (not per-org) -- FREE/STARTER/GROWTH placeholders,
// seeded by PlanService on startup. See prompts/features/billing-subscription.md.
@Entity
@Table(name = "plan")
@Getter
@Setter
@NoArgsConstructor
public class PlanPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "plan_gen")
    @TableGenerator(
        name = "plan_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "plan_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 16)
    private PlanCode code;

    // plan name like free/starter/growth/enterprise
    @Column(nullable = false, length = 64)
    private String name;

    // max non-DISABLED `account` rows an org on this plan may have
    @Column(name = "seat_limit", nullable = false)
    private int seatLimit;

    // flat price per billing period, minor units (paise); 0 for FREE
    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    // ISO 4217, e.g. INR (right now single curreny)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 16)
    private BillingPeriod billingPeriod;

    // Razorpay's own Plan id; null for FREE (never charged)
    @Column(name = "razorpay_plan_id", nullable = true, length = 64)
    private String razorpayPlanId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    // true only for ENTERPRISE -- SubscriptionService.subscribe rejects a self-serve
    // POST /api/billing/subscribe for any plan with this set (409, "contact sales")
    // before touching Razorpay or org_subscription at all.
    @Column(name = "is_custom_pricing", nullable = false)
    private boolean customPricing;
}
