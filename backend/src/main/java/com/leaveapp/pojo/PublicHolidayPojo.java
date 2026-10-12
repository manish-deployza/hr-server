package com.leaveapp.pojo;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// weekly (Sunday) and optional/floating holidays are NOT here -- see
// prompts/features/database-schema.md#public_holiday
@Entity
@Table(
    name = "public_holiday",
    uniqueConstraints = @UniqueConstraint(name = "uk_pub_holiday_org_date", columnNames = {"org_id", "holiday_date"})
)
@Getter
@Setter
@NoArgsConstructor
public class PublicHolidayPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "pub_holiday_gen")
    @TableGenerator(
        name = "pub_holiday_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "public_holiday_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // plain FK column, no @ManyToOne -- matches every other FK in this codebase
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 128)
    private String name;
}
