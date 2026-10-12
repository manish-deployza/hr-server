package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.pojo.OrgPojo;

public class OrgServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void create_persists() {
        OrgPojo saved = orgSvc.create("Acme Inc", "acme-a", "hr@acme.test", "IN");
        assertNotNull(saved.getId());
        assertEquals("ACTIVE", saved.getStatus().name());
    }

    @Test
    public void create_invalidOrgKey_throws() {
        assertThrows(BadRequestException.class, //
            () -> orgSvc.create("Bad", "Not Valid!", "hr@x.test", "IN"));
    }

    @Test
    public void create_underscoreOrgKey_succeeds() {
        OrgPojo saved = orgSvc.create("Acme Inc", "acme_co", "hr@acme.test", "IN");
        assertNotNull(saved.getId());
    }

    @Test
    public void create_hyphenOrgKey_succeeds() {
        OrgPojo saved = orgSvc.create("Acme Inc", "acme-co", "hr@acme.test", "IN");
        assertNotNull(saved.getId());
    }

    @Test
    public void create_duplicateOrgKey_throws() {
        orgSvc.create("First", "acme-b", "hr@acme.test", "IN");
        assertThrows(ConflictException.class, //
            () -> orgSvc.create("Second", "acme-b", "hr2@acme.test", "IN"));
    }

    @Test
    public void update_setsNameAndEmail() {
        OrgPojo org = orgSvc.create(null, "acme-u", "hr@acme.test", "IN");
        OrgPojo updated = orgSvc.update(org.getId(), "Acme Ltd", "people@acme.test");
        assertEquals("Acme Ltd", updated.getName());
        assertEquals("people@acme.test", updated.getHrNotifyEmail());
    }

    @Test
    public void update_nullArg_leavesFieldUnchanged() {
        OrgPojo org = orgSvc.create("Keep Me", "acme-k", "hr@acme.test", "IN");
        OrgPojo updated = orgSvc.update(org.getId(), null, "new@acme.test");
        assertEquals("Keep Me", updated.getName());
        assertEquals("new@acme.test", updated.getHrNotifyEmail());
    }

    @Test
    public void update_blankEmail_throws() {
        OrgPojo org = orgSvc.create(null, "acme-b2", "hr@acme.test", "IN");
        assertThrows(BadRequestException.class, () -> orgSvc.update(org.getId(), null, "  "));
    }

    @Test
    public void update_emailWithoutAt_throws() {
        OrgPojo org = orgSvc.create(null, "acme-n2", "hr@acme.test", "IN");
        assertThrows(BadRequestException.class, () -> orgSvc.update(org.getId(), null, "notanemail"));
    }

    @Test
    public void findById_missing_throws() {
        assertThrows(NotFoundException.class, () -> orgSvc.findById(999L));
    }

    @Test
    public void findByOrgKeyOrNull_noMatch_null() {
        assertNull(orgSvc.findByOrgKeyOrNull("missing"));
    }
}
