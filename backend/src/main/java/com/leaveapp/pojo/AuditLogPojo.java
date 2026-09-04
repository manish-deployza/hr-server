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

// hand-written from the service layer at sensitive mutation points only (HR override,
// imposed LOP, exit finalize, leave-type config changes) -- not a generic entity listener;
// see prompts/features/database-schema.md#audit_log
// No index on (entity_type, entity_id) despite AuditLogRepo.findByEntityTypeAndEntityId
// existing -- nothing in src/main calls it yet (see V34); add the index back once a
// real caller exists.
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogPojo extends CreatedOnlyPojo {

    /* Variables */

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

    // what was changed -- open-ended, not an enum: nothing writes this table yet
    // (see review GAPS), so the value set isn't settled.
    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // e.g. LOP_IMPOSED, LEAVE_TYPE_UPDATED
    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "performed_by", nullable = false)
    private Long performedBy;

    // MySQL's native JSON validates on write and H2's JSON type doesn't behave the
    // same way -- TEXT avoids the dialect mismatch, matches reason/hrNotes elsewhere.
    @Column(name = "before_json", nullable = true, columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", nullable = true, columnDefinition = "TEXT")
    private String afterJson;
}
