package com.leaveapp.pojo;

import java.time.Instant;

import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.enums.NotificationType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// see prompts/features/database-schema.md#notification and notifications.md's trigger matrix
@Entity
// idx_notification_user_created backs NotificationRepo's per-user, newest-first lists.
@Table(
    name = "notification",
    indexes = @Index(name = "idx_notification_user_created", columnList = "user_id, created_at")
)
@Getter
@Setter
@NoArgsConstructor
public class NotificationPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // is_read flips after insert
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "notif_gen")
    @TableGenerator(
        name = "notif_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "notification_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // the user this notification is for (recipient)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    // e.g. LEAVE_REQUEST / 42 -- what this notification is about, if anything. Java
    // field shortened from relatedEntityType; column name unchanged, no migration.
    @Enumerated(EnumType.STRING)
    @Column(name = "related_entity_type", nullable = true, length = 32)
    private EntityType relEntityType;

    @Column(name = "related_entity_id", nullable = true)
    private Long relatedEntityId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /* Protected Methods */

    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
