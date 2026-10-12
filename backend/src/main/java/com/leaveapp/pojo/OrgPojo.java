package com.leaveapp.pojo;

import com.leaveapp.model.enums.OrgStatus;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
public class OrgPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // name/country/status change after insert
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "organization_gen")
    @TableGenerator(
        name = "organization_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "org_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // nullable -- not collected at signup (less friction); set later via settings
    // (not yet built). See prompts/features/multi-tenancy.md.
    @Column(nullable = true, length = 128)
    private String name;

    @Column(name = "org_key", nullable = false, unique = true, length = 64)
    private String orgKey;

    @Column(name = "hr_notify_email", nullable = false, length = 190)
    private String hrNotifyEmail;

    // ISO 3166-1 alpha-2, upper-cased (same convention as AddressPojo.country). Set by
    // AuthFlow.signup, the only path that creates an org.
    @Column(nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrgStatus status;

    /* Protected Methods */

    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
