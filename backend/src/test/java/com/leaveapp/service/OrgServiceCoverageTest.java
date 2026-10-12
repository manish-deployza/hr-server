package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.pojo.OrgPojo;

// Gap-filler for OrgServiceTest: the PATCH arms of update() it does not reach.
public class OrgServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void update_blankName_clearsNameToNull() {
        OrgPojo org = orgSvc.create("Acme", "acme-orgc-1", "hr@acme.test", "IN");

        OrgPojo updated = orgSvc.update(org.getId(), "   ", null);

        assertNull(updated.getName());
        assertEquals("hr@acme.test", updated.getHrNotifyEmail()); // null email arg = unchanged
    }

    @Test
    public void update_nameIsTrimmed() {
        OrgPojo org = orgSvc.create(null, "acme-orgc-2", "hr@acme.test", "IN");

        assertEquals("Acme Ltd", orgSvc.update(org.getId(), "  Acme Ltd ", null).getName());
    }

    @Test
    public void update_bothArgsNull_changesNothing() {
        OrgPojo org = orgSvc.create("Keep", "acme-orgc-3", "hr@acme.test", "IN");

        OrgPojo updated = orgSvc.update(org.getId(), null, null);

        assertEquals("Keep", updated.getName());
        assertEquals("hr@acme.test", updated.getHrNotifyEmail());
    }

    @Test
    public void update_emailIsTrimmed() {
        OrgPojo org = orgSvc.create("Acme", "acme-orgc-4", "hr@acme.test", "IN");

        assertEquals("new@acme.test", orgSvc.update(org.getId(), null, "  new@acme.test ").getHrNotifyEmail());
    }

    @Test
    public void update_emptyEmail_throwsBadRequest() {
        OrgPojo org = orgSvc.create("Acme", "acme-orgc-5", "hr@acme.test", "IN");

        assertThrows(BadRequestException.class, () -> orgSvc.update(org.getId(), null, ""));
    }

    @Test
    public void create_storesCountry() {
        assertEquals("IN", orgSvc.create("Acme", "acme-orgc-6", "hr@acme.test", "IN").getCountry());
    }

    @Test
    public void create_orgKeyBoundaryLengths() {
        assertEquals("a", orgSvc.create("A", "a", "hr@acme.test", "IN").getOrgKey());
        assertEquals(64, orgSvc.create("B", "b".repeat(64), "hr@acme.test", "IN").getOrgKey().length());
        assertThrows(BadRequestException.class, () -> orgSvc.create("C", "c".repeat(65), "hr@acme.test", "IN"));
        assertThrows(BadRequestException.class, () -> orgSvc.create("D", "", "hr@acme.test", "IN"));
        assertThrows(BadRequestException.class, () -> orgSvc.create("E", "UPPER", "hr@acme.test", "IN"));
    }
}
