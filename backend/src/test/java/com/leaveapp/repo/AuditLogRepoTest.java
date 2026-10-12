package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.AuditLogPojo;
import com.leaveapp.model.enums.EntityType;

public class AuditLogRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private AuditLogRepo auditLogRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        AuditLogPojo saved = auditLogRepo.save(AuditLogTestData.anEntry(EntityType.LEAVE_TYPE, 1L));
        assertNotNull(saved.getId());
    }

    @Test
    public void save_nullEntityType_throws() {
        AuditLogPojo a = AuditLogTestData.anEntry(EntityType.LEAVE_TYPE, 1L);
        a.setEntityType(null);
        assertThrows(DataIntegrityViolationException.class, () -> auditLogRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullEntityId_throws() {
        AuditLogPojo a = AuditLogTestData.anEntry(EntityType.LEAVE_TYPE, 1L);
        a.setEntityId(null);
        assertThrows(DataIntegrityViolationException.class, () -> auditLogRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullAction_throws() {
        AuditLogPojo a = AuditLogTestData.anEntry(EntityType.LEAVE_TYPE, 1L);
        a.setAction(null);
        assertThrows(DataIntegrityViolationException.class, () -> auditLogRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullPerformedBy_throws() {
        AuditLogPojo a = AuditLogTestData.anEntry(EntityType.LEAVE_TYPE, 1L);
        a.setPerformedBy(null);
        assertThrows(DataIntegrityViolationException.class, () -> auditLogRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        AuditLogPojo a = AuditLogTestData.anEntry(EntityType.LEAVE_TYPE, 1L);
        a.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> auditLogRepo.saveAndFlush(a));
    }
}
