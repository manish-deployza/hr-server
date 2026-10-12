package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.pojo.EmpPhotoPojo;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;

// Gap-filler for EmpServiceTest: the lookup edge cases, every PATCH arm of the apply*
// methods, and every arm of the magic-byte photo sniff.
public class EmpServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    private static final byte[] JPEG_BYTES = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };

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
    public void findByUserId_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> empSvc.findByUserId(987654L));
    }

    @Test
    public void findByUserIdOrNull_nullId_returnsNull() {
        assertNull(empSvc.findByUserIdOrNull(null));
    }

    @Test
    public void findByUserIdOrNull_unknownId_returnsNull() {
        assertNull(empSvc.findByUserIdOrNull(987654L));
    }

    @Test
    public void findByManager_returnsOnlyDirectReports() {
        UserPojo mgr = aUser("acme-empc-1", "mgr@acme.test");
        empSvc.createOrgAdminProfile(mgr.getId(), mgr.getOrgId());
        UserPojo r = userSvc.createUser(mgr.getOrgId(), "rep@acme.test", pwd("s3cret!!"), UserRole.EMPLOYEE);
        empSvc.createOrgAdminProfile(r.getId(), r.getOrgId()).setManagerId(mgr.getId());

        List<EmpPojo> reports = empSvc.findByManager(mgr.getId());

        assertEquals(1, reports.size());
        assertEquals(r.getId(), reports.get(0).getUserId());
        assertTrue(empSvc.findByManager(r.getId()).isEmpty());
    }

    @Test
    public void applyAdminFields_allFieldsSent_areAllWritten() {
        EmpPojo emp = anEmp("acme-empc-2", "all@acme.test");

        empSvc.applyAdminFields(emp, " CODE-9 ", "New Name", "Lead", "Ops", " CONTRACT ",
            LocalDate.of(2021, 2, 3), LocalDate.of(2030, 1, 1), new BigDecimal("100.50"));

        assertEquals("CODE-9", emp.getEmployeeCode());
        assertEquals("New Name", emp.getName());
        assertEquals("Lead", emp.getDesignation());
        assertEquals("Ops", emp.getDepartment());
        assertEquals("CONTRACT", emp.getEmployeeType());
        assertEquals(LocalDate.of(2021, 2, 3), emp.getDateOfJoining());
        assertEquals(LocalDate.of(2030, 1, 1), emp.getDateOfExit());
        assertEquals(0, new BigDecimal("100.50").compareTo(emp.getBasicSalary()));
    }

    @Test
    public void applyAdminFields_nullsAndBlanks_leaveEverythingUnchanged() {
        EmpPojo emp = anEmp("acme-empc-3", "none@acme.test");
        String code = emp.getEmployeeCode();
        LocalDate joined = emp.getDateOfJoining();

        empSvc.applyAdminFields(emp, null, null, null, null, null, null, null, null);
        assertEquals(code, emp.getEmployeeCode());
        assertEquals("REGULAR", emp.getEmployeeType());

        empSvc.applyAdminFields(emp, "   ", null, null, null, "   ", null, null, null); // blank = unchanged
        assertEquals(code, emp.getEmployeeCode());
        assertEquals("REGULAR", emp.getEmployeeType());
        assertEquals(joined, emp.getDateOfJoining());
        assertNull(emp.getDateOfExit());
        assertNull(emp.getBasicSalary());
    }

    @Test
    public void applyDirectoryFields_setsWorkLocation_nullLeavesIt() {
        EmpPojo emp = anEmp("acme-empc-4", "wl@acme.test");

        empSvc.applyDirectoryFields(emp, null);
        assertNull(emp.getWorkLocation());

        empSvc.applyDirectoryFields(emp, "Pune");
        assertEquals("Pune", emp.getWorkLocation());

        empSvc.applyDirectoryFields(emp, null);
        assertEquals("Pune", emp.getWorkLocation());
    }

    @Test
    public void setPhoto_nullBytes_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-5", "p1@acme.test");
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(emp.getUserId(), null, "image/png"));
    }

    @Test
    public void setPhoto_emptyBytes_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-6", "p2@acme.test");
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(emp.getUserId(), new byte[0], "image/png"));
    }

    @Test
    public void setPhoto_exactlyMaxBytes_isAccepted() {
        EmpPojo emp = anEmp("acme-empc-7", "p3@acme.test");
        byte[] atLimit = new byte[2 * 1024 * 1024]; // test.properties app.photo.max-bytes
        System.arraycopy(JPEG_BYTES, 0, atLimit, 0, JPEG_BYTES.length);

        photoSvc.setPhoto(emp.getUserId(), atLimit, "image/jpeg");

        assertEquals("image/jpeg", photoSvc.findOrNull(emp.getUserId()).getContentType());
    }

    @Test
    public void setPhoto_jpeg_isAccepted() {
        EmpPojo emp = anEmp("acme-empc-8", "p4@acme.test");

        EmpPhotoPojo saved = photoSvc.setPhoto(emp.getUserId(), JPEG_BYTES, "image/jpeg");

        assertEquals("image/jpeg", saved.getContentType());
        assertArrayEquals(JPEG_BYTES, saved.getBytes());
    }

    @Test
    public void setPhoto_webp_isAccepted() {
        EmpPojo emp = anEmp("acme-empc-9", "p5@acme.test");

        EmpPhotoPojo saved = photoSvc.setPhoto(emp.getUserId(), webp(), "image/webp");

        assertEquals("image/webp", saved.getContentType());
    }

    @Test
    public void setPhoto_nullDeclaredType_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-10", "p6@acme.test");
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(emp.getUserId(), JPEG_BYTES, null));
    }

    @Test
    public void setPhoto_unknownUserWithValidBytes_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> photoSvc.setPhoto(987654L, JPEG_BYTES, "image/jpeg"));
    }

    // 2 and 3 bytes: shorter than the JPEG (3) / PNG (4) signatures -> startsWith's length guard
    @Test
    public void setPhoto_tooShortForAnySignature_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-11", "p7@acme.test");
        assertThrows(BadRequestException.class, //
            () -> photoSvc.setPhoto(emp.getUserId(), new byte[] { (byte) 0xFF, (byte) 0xD8 }, "image/jpeg"));
        assertThrows(BadRequestException.class, //
            () -> photoSvc.setPhoto(emp.getUserId(), new byte[] { (byte) 0x89, 0x50, 0x4E }, "image/png"));
    }

    // right length, right prefix, wrong last signature byte -> the loop's mismatch arm
    @Test
    public void setPhoto_signaturePrefixThenMismatch_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-12", "p8@acme.test");
        byte[] almostJpeg = { (byte) 0xFF, (byte) 0xD8, 0x00, 0x00 };
        byte[] almostPng = { (byte) 0x89, 0x50, 0x4E, 0x00 };
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(emp.getUserId(), almostJpeg, "image/jpeg"));
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(emp.getUserId(), almostPng, "image/png"));
    }

    // 11 bytes: one short of the 12-byte RIFF....WEBP header
    @Test
    public void setPhoto_webpHeaderTooShort_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-13", "p9@acme.test");
        byte[] shortHeader = Arrays.copyOf(webp(), 11);
        assertThrows(BadRequestException.class, () -> photoSvc.setPhoto(emp.getUserId(), shortHeader, "image/webp"));
    }

    // corrupt each of the 8 checked header bytes in turn (RIFF at 0-3, WEBP at 8-11)
    @Test
    public void setPhoto_webpHeaderWithAnyWrongByte_throwsBadRequest() {
        EmpPojo emp = anEmp("acme-empc-14", "p10@acme.test");
        for (int pos : new int[] { 0, 1, 2, 3, 8, 9, 10, 11 }) {
            byte[] bad = webp();
            bad[pos] = 'x';
            assertThrows(BadRequestException.class, //
                () -> photoSvc.setPhoto(emp.getUserId(), bad, "image/webp"), "byte " + pos);
        }
    }

    /* Private Helper Methods */

    private UserPojo aUser(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        return userSvc.createUser(org.getId(), email, pwd("s3cret!!"), UserRole.ORG_ADMIN);
    }

    private EmpPojo anEmp(String orgKey, String email) {
        UserPojo user = aUser(orgKey, email);
        return empSvc.createOrgAdminProfile(user.getId(), user.getOrgId());
    }

    private static byte[] webp() {
        return "RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
