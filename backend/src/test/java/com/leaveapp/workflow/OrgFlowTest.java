package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.OrgData;
import com.leaveapp.model.form.OrgForm;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.service.OrgService;

public class OrgFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private OrgFlow flow;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void get_returnsTheOrgAsApiShape() {
        OrgPojo org = orgSvc.create("Acme Inc", "acme-orgf-1", "hr@acme.test", "IN");

        OrgData data = flow.get(org.getId());

        assertEquals(org.getId(), data.getId());
        assertEquals("Acme Inc", data.getName());
        assertEquals("acme-orgf-1", data.getOrgKey());
        assertEquals("hr@acme.test", data.getHrNotifyEmail());
        assertEquals("ACTIVE", data.getStatus());
    }

    @Test
    public void get_unknownOrg_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> flow.get(987654L));
    }

    @Test
    public void get_nullName_isReturnedAsNull() {
        OrgPojo org = orgSvc.create(null, "acme-orgf-2", "hr@acme.test", "IN");

        assertNull(flow.get(org.getId()).getName());
    }

    @Test
    public void update_appliesNameAndEmail_returnsUpdatedShape() {
        OrgPojo org = orgSvc.create("Old", "acme-orgf-3", "old@acme.test", "IN");
        OrgForm form = new OrgForm();
        form.setName("New Name");
        form.setHrNotifyEmail("new@acme.test");

        OrgData data = flow.update(org.getId(), form);

        assertEquals("New Name", data.getName());
        assertEquals("new@acme.test", data.getHrNotifyEmail());
        assertEquals("acme-orgf-3", data.getOrgKey()); // login key is never touched
        assertEquals("ACTIVE", data.getStatus());
    }

    @Test
    public void update_emptyForm_changesNothing() {
        OrgPojo org = orgSvc.create("Keep", "acme-orgf-4", "keep@acme.test", "IN");

        OrgData data = flow.update(org.getId(), new OrgForm());

        assertEquals("Keep", data.getName());
        assertEquals("keep@acme.test", data.getHrNotifyEmail());
    }

    @Test
    public void update_invalidEmail_throwsBadRequest() {
        OrgPojo org = orgSvc.create("Acme", "acme-orgf-5", "hr@acme.test", "IN");
        OrgForm form = new OrgForm();
        form.setHrNotifyEmail("not-an-email");

        assertThrows(BadRequestException.class, () -> flow.update(org.getId(), form));
    }

    @Test
    public void update_unknownOrg_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> flow.update(987654L, new OrgForm()));
    }
}
