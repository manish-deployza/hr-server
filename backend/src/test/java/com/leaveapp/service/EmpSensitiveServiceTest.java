package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;

// phone/address moved off EmpPojo (PII, code review) -- see EmpService's own
// createOrgAdminProfile/setPhoto tests for the rest of the employee profile.
public class EmpSensitiveServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void applyContactFields_setsPhoneAndAddress() {
        UserPojo user = anOrgAdmin("acme-sens-1", "jane@acme.test");
        EmpSensitivePojo sens = sensSvc.createEmpty(user.getId());

        sensSvc.applyContactFields(sens, "555-0100", "1 Main St");
        assertEquals("555-0100", sens.getPhone());
        assertEquals("1 Main St", sens.getContactAddress());
    }

    @Test
    public void applyContactFields_nullFieldLeavesExistingValue() {
        UserPojo user = anOrgAdmin("acme-sens-2", "jane2@acme.test");
        EmpSensitivePojo sens = sensSvc.createEmpty(user.getId());
        sensSvc.applyContactFields(sens, "555-0100", "1 Main St");

        // PATCH: null phone must not wipe the stored value; address still updates.
        sensSvc.applyContactFields(sens, null, "2 Main St");
        assertEquals("555-0100", sens.getPhone());
        assertEquals("2 Main St", sens.getContactAddress());
    }

    @Test
    public void applyContactFields_trimsAndRejectsOverLength() {
        UserPojo user = anOrgAdmin("acme-sens-3", "jane3@acme.test");
        EmpSensitivePojo sens = sensSvc.createEmpty(user.getId());

        sensSvc.applyContactFields(sens, "  555-0100  ", "");
        assertEquals("555-0100", sens.getPhone());
        assertEquals("", sens.getContactAddress());

        assertThrows(BadRequestException.class,
            () -> sensSvc.applyContactFields(sens, "x".repeat(21), null));
        assertThrows(BadRequestException.class,
            () -> sensSvc.applyContactFields(sens, null, "x".repeat(256)));
    }

    /* Private Helper Methods */

    private UserPojo anOrgAdmin(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        return userSvc.createUser(org.getId(), email, pwd("s3cret!!"), UserRole.ORG_ADMIN);
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
