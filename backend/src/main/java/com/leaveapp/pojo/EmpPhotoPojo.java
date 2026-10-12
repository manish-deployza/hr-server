package com.leaveapp.pojo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Self-service profile photo -- see prompts/features/employee-auth-and-profile.changes.md.
// Its own table, not columns on `employee`: every findByOrgId/findByManagerId read of
// EmpPojo would otherwise load the MEDIUMBLOB too. A row exists only while a photo is set
// (clearing deletes it), so both columns are required. 1:1 with user, PK = user id, same
// pattern as EmpPojo. No cloud storage lib in this project, so the bytes live in the row.
@Entity
@Table(name = "employee_photo")
@Getter
@Setter
@NoArgsConstructor
public class EmpPhotoPojo extends AuditedPojo {

    /* Variables */

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] bytes;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;
}
