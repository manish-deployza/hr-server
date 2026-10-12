package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.AuditAction;
import com.leaveapp.pojo.AuditLogPojo;
import com.leaveapp.model.enums.EntityType;

public class AuditLogTestData {

    /* Public Methods */

    public static AuditLogPojo anEntry(EntityType entityType, Long entityId) {
        AuditLogPojo a = new AuditLogPojo();
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setAction(AuditAction.EMPLOYEE_DETAILS_UPDATED);
        a.setPerformedBy(1L);
        a.setCreatedAt(Instant.now());
        return a;
    }
}
