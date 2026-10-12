package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.pojo.EmpPhotoPojo;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.enums.UserRole;

public class EmpServiceTest extends RepositoryTestSupport {

    /* Variables */

    // Minimal valid 1x1 PNG (signature + IHDR/IDAT/IEND) -- real bytes, not just the
    // magic-byte prefix, so it exercises the same sniff path a real upload would.
    private static final byte[] PNG_BYTES = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
        (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
        0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF, (byte) 0xC0, 0x00,
        0x00, 0x00, 0x03, 0x00, 0x01, 0x27, (byte) 0xFA, (byte) 0xF7,
        0x39, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    @Autowired
    private EmpService empSvc;

    @Autowired
    private EmpPhotoService photoSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void createOrgAdminProfile_persistsWithDefaults() {
        UserPojo user = anOrgAdmin("acme-emp-1", "jane@acme.test");
        EmpPojo emp = empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        assertEquals(user.getId(), emp.getUserId());
        assertEquals(user.getOrgId(), emp.getOrgId());
        assertNull(emp.getEmployeeCode()); // no placeholder -- HR sets it later
        assertEquals("REGULAR", emp.getEmployeeType());
        assertNull(emp.getDateOfJoining());
        assertEquals(EmpStatus.ACTIVE, emp.getStatus());
    }

    @Test
    public void existsManager_trueWhenSomeoneReports() {
        UserPojo mgr = anOrgAdmin("acme-emp-3", "mgr@acme.test");
        empSvc.createOrgAdminProfile(mgr.getId(), mgr.getOrgId());

        UserPojo reportUser = userSvc.createUser(mgr.getOrgId(), "report@acme.test", pwd("s3cret!!"), UserRole.EMPLOYEE);
        EmpPojo report = empSvc.createOrgAdminProfile(reportUser.getId(), reportUser.getOrgId());
        report.setManagerId(mgr.getId());

        assertTrue(empSvc.existsManager(mgr.getId()));
        assertFalse(empSvc.existsManager(reportUser.getId()));
    }

    @Test
    public void setPhoto_validPngPersistsBytesAndType() {
        UserPojo user = anOrgAdmin("acme-emp-4", "jane4@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        EmpPhotoPojo updated = photoSvc.setPhoto(user.getId(), PNG_BYTES, "image/png");
        assertEquals("image/png", updated.getContentType());
        assertTrue(java.util.Arrays.equals(PNG_BYTES, updated.getBytes()));
    }

    @Test
    public void setPhoto_declaredTypeMustMatchActualBytes() {
        UserPojo user = anOrgAdmin("acme-emp-5", "jane5@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        // PNG bytes, but the caller claims JPEG -- sniff must win over the declared type.
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(user.getId(), PNG_BYTES, "image/jpeg"));
    }

    @Test
    public void setPhoto_rejectsNonImageBytes() {
        UserPojo user = anOrgAdmin("acme-emp-6", "jane6@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        byte[] notAnImage = "hello world".getBytes(StandardCharsets.UTF_8);
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(user.getId(), notAnImage, "image/png"));
    }

    @Test
    public void setPhoto_rejectsOverSizeLimit() {
        UserPojo user = anOrgAdmin("acme-emp-7", "jane7@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());

        byte[] oversized = new byte[3 * 1024 * 1024];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(user.getId(), oversized, "image/png"));
    }

    @Test
    public void clearPhoto_removesStoredPhoto() {
        UserPojo user = anOrgAdmin("acme-emp-8", "jane8@acme.test");
        empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());
        photoSvc.setPhoto(user.getId(), PNG_BYTES, "image/png");

        photoSvc.clearPhoto(user.getId());
        assertEquals(null, photoSvc.findOrNull(user.getId()));
        assertFalse(photoSvc.hasPhoto(user.getId()));
        photoSvc.clearPhoto(user.getId()); // idempotent
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
