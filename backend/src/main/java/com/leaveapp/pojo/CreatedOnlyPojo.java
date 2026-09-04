package com.leaveapp.pojo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

// createdAt only, for append-only / never-updated entities.
@MappedSuperclass
@Getter
@Setter
public abstract class CreatedOnlyPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
