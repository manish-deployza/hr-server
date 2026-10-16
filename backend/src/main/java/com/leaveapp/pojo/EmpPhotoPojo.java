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
// (clearing deletes it). 1:1 with user, PK = user id, same pattern as EmpPojo. The image
// lives in the media bucket (storage_key) when app.media.bucket is set; otherwise (local
// dev, tests, and photos saved before the bucket existed) the bytes live in this row.
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

    // Null once the photo is in the bucket (see storageKey)
    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    private byte[] bytes;

    // Bucket-relative object key, null while the bytes are still in this row
    @Column(name = "storage_key", length = 120)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;
}
