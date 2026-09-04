package com.leaveapp.pojo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

// createdAt/updatedAt for entities that are mutated after creation.
@MappedSuperclass
@Getter
@Setter
public abstract class AuditedPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /* Protected Methods */

    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
