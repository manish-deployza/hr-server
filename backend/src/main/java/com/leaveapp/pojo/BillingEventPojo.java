package com.leaveapp.pojo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Diagnostics + webhook-idempotency log. Never read by any business-logic path except
// the dedup insert itself (SubscriptionService.insertEventIfNew) -- the unique
// constraint on razorpayEventId *is* the idempotency mechanism, not a bystander to it.
// See prompts/features/billing-subscription.md.
@Entity
@Table(name = "billing_event")
@Getter
@Setter
@NoArgsConstructor
public class BillingEventPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "billing_event_gen")
    @TableGenerator(
        name = "billing_event_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "billing_event_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // Razorpay's own event id when the gateway supplies one; falls back to a SHA-256
    // hash of the raw payload otherwise -- see BillingWebhookController.eventId. Either
    // way this is the dedup key: the unique constraint is what makes a duplicate
    // delivery fail the insert instead of re-applying the transition.
    @Column(name = "razorpay_event_id", nullable = false, unique = true, length = 64)
    private String razorpayEventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    // resolved from the payload's subscription id where possible; null if it doesn't
    // map to a known org_subscription row (logged, not fatal)
    @Column(name = "org_id", nullable = true)
    private Long orgId;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
