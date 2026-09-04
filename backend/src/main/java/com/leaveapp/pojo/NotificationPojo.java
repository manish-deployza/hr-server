package com.leaveapp.pojo;

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

// see prompts/features/database-schema.md#notification and notifications.md's trigger matrix
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class NotificationPojo extends CreatedOnlyPojo {

    /* Variables */

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

    // e.g. LEAVE_APPLIED, LEAVE_APPROVED, LOP_IMPOSED -- open-ended, not an enum: no
    // notification is written yet (see review GAPS), so the value set isn't settled.
    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    // e.g. LEAVE_REQUEST / 42 -- what this notification is about, if anything. Java
    // field shortened from relatedEntityType; column name unchanged, no migration.
    @Column(name = "related_entity_type", nullable = true, length = 64)
    private String relEntityType;

    @Column(name = "related_entity_id", nullable = true)
    private Long relatedEntityId;

    @Column(name = "is_read", nullable = false)
    private boolean read;
}
