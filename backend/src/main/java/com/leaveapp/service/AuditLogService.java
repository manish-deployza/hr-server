package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// AuditLogService -> AuditLogRepo -> DB (append-only audit_log rows)
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.enums.AuditAction;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.pojo.AuditLogPojo;
import com.leaveapp.repo.AuditLogRepo;

// Hand-written audit trail for sensitive mutations. Callers pass field *names* only in
// afterJson, never the values -- bank/ID numbers must not be copied into a second table.
@Service
public class AuditLogService {

    /* Variables */

    @Autowired
    private AuditLogRepo auditRepo;

    /* Public Methods */

    @Transactional(rollbackFor = Exception.class)
    public void record(EntityType entityType, Long entityId, AuditAction action, Long performedBy,
        String afterJson) {
        AuditLogPojo a = new AuditLogPojo();
        a.setCreatedAt(Instant.now());
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setAction(action);
        a.setPerformedBy(performedBy);
        a.setAfterJson(afterJson);
        auditRepo.save(a);
    }
}
