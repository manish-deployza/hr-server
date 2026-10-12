package com.leaveapp.pojo;

import java.time.Instant;

import com.leaveapp.model.enums.AuditAction;
import com.leaveapp.model.enums.EntityType;

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

// hand-written from the service layer at sensitive mutation points only (HR override,
// imposed LOP, exit finalize, leave-type config changes) -- not a generic entity listener;
// see prompts/features/database-schema.md#audit_log
// No index on (entity_type, entity_id): no query against it exists yet (AuditLogRepo
// has no custom finders) -- add it once a real caller does, same policy as
// LeaveRequestPojo's own no-index note.
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "audit_log_gen")
    @TableGenerator(
        name = "audit_log_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "audit_log_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // what was changed
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Column(name = "performed_by", nullable = false)
    private Long performedBy;

    // MySQL's native JSON validates on write and H2's JSON type doesn't behave the
    // same way -- TEXT avoids the dialect mismatch, matches reason/hrNotes elsewhere.
    @Column(name = "before_json", nullable = true, columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", nullable = true, columnDefinition = "TEXT")
    private String afterJson;
}
