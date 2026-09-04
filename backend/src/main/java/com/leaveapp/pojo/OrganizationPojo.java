package com.leaveapp.pojo;

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

@Entity
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationPojo extends CreatedOnlyPojo {

    /* Variables */

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "organization_gen")
    @TableGenerator(
        name = "organization_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "organization_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // nullable -- not collected at signup (less friction); set later via settings
    // (not yet built). See prompts/features/multi-tenancy.md.
    @Column(nullable = true, length = 128)
    private String name;

    // admin contact name, nullable, same lifecycle as `name`
    @Column(name = "admin_name", nullable = true, length = 128)
    private String adminName;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "hr_notify_email", nullable = false, length = 190)
    private String hrNotifyEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrgStatus status;

    // separate from `status` -- soft-delete flag, never a hard DELETE.
    @Column(name = "is_active", nullable = false)
    private boolean active;
}
