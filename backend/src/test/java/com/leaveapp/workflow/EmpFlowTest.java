package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.PhotoData;
import com.leaveapp.model.data.SessionData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.ProfileForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// The employee's own profile: read, edit phone/address, photo upload / fetch / delete.
// "Bare" users are made through UserService + EmpService directly, which (unlike
// HrEmpFlow.create) leaves no employee_sensitive row -- the pre-sensitive-module case.
public class EmpFlowTest extends RepositoryTestSupport {

    /* Variables */

    private static final byte[] PNG_BYTES = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    @Autowired
    private EmpFlow flow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    @Test
    public void getProfile_userWithoutSensitiveRow_leavesPhoneAndAddressNull() {
        UserPojo user = aBareUser("acme-empf-1", "bare@acme.test");

        SessionData data = flow.getProfile(user.getId());

        assertEquals(user.getId(), data.getId());
        assertEquals("bare@acme.test", data.getEmail());
        assertFalse(data.isEmailVerified());
        assertEquals("ORG_ADMIN", data.getRole());
        assertEquals("ACTIVE", data.getStatus());
        assertNull(data.getEmployeeCode()); // no placeholder until HR sets one
        assertEquals("REGULAR", data.getEmployeeType());
        assertNull(data.getDateOfJoining());
        assertNull(data.getPhone());
        assertNull(data.getAddress());
        assertNull(data.getStructuredAddress().getCity());
        assertFalse(data.isHasPhoto());
    }

    @Test
    public void getProfile_userWithSensitiveRow_includesPhoneAddressAndOrg() {
        OrgPojo org = orgSvc.create("Acme Inc", "acme-empf-2", "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        EmpForm form = new EmpForm();
        form.setEmail("full@acme.test");
        form.setRole(UserRole.EMPLOYEE);
        form.setName("Fay Full");
        form.setPhone("+91 98765 43210");
        Long id = hrEmpFlow.create(org.getId(), form);
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(id);
        sens.getAddress().setCity("Pune");

        SessionData data = flow.getProfile(id);

        assertEquals("Fay Full", data.getName());
        assertEquals("+91 98765 43210", data.getPhone());
        assertEquals("Pune", data.getStructuredAddress().getCity());
        assertEquals("Acme Inc", data.getOrganizationName());
        assertEquals("acme-empf-2", data.getOrgKey());
        assertEquals("INVITED", data.getStatus());
    }

    @Test
    public void getProfile_withoutManager_managerFieldsAreNull_andIsNotAManager() {
        UserPojo user = aBareUser("acme-empf-3", "top@acme.test");

        SessionData data = flow.getProfile(user.getId());

        assertNull(data.getManagerId());
        assertNull(data.getManagerName());
        assertFalse(data.getIsManager());
    }

    @Test
    public void getProfile_withManager_resolvesManagerNameAndFlagsTheManager() {
        UserPojo mgr = aBareUser("acme-empf-4", "boss@acme.test");
        empSvc.findByUserId(mgr.getId()).setName("Big Boss");
        UserPojo report = userSvc.createUser(mgr.getOrgId(), "rep@acme.test", pwd("s3cret!!"), UserRole.EMPLOYEE);
        empSvc.createOrgAdminProfile(report.getId(), mgr.getOrgId()).setManagerId(mgr.getId());

        SessionData asReport = flow.getProfile(report.getId());
        SessionData asManager = flow.getProfile(mgr.getId());

        assertEquals(mgr.getId(), asReport.getManagerId());
        assertEquals("Big Boss", asReport.getManagerName());
        assertFalse(asReport.getIsManager());
        assertTrue(asManager.getIsManager());
    }

    @Test
    public void getProfile_unknownUser_throwsNotFound() {
        assertThrows(NotFoundException.class, () -> flow.getProfile(987654L));
    }

    @Test
    public void updateProfile_userWithoutSensitiveRow_createsItAndAppliesFields() {
        UserPojo user = aBareUser("acme-empf-5", "up1@acme.test");
        assertNull(sensSvc.findByUserIdOrNull(user.getId()));
        ProfileForm form = new ProfileForm();
        form.setPhone("555-0100");
        form.setAddress("1 Main St");

        SessionData data = flow.updateProfile(user.getId(), form);

        assertEquals("555-0100", data.getPhone());
        assertEquals("1 Main St", data.getAddress());
        assertNotNull(sensSvc.findByUserIdOrNull(user.getId()));
    }

    @Test
    public void updateProfile_existingSensitiveRow_isPatched_nullFieldKept() {
        UserPojo user = aBareUser("acme-empf-6", "up2@acme.test");
        ProfileForm first = new ProfileForm();
        first.setPhone("555-0100");
        first.setAddress("1 Main St");
        flow.updateProfile(user.getId(), first);

        ProfileForm second = new ProfileForm();
        second.setAddress("2 Side St"); // phone not sent -> unchanged
        SessionData data = flow.updateProfile(user.getId(), second);

        assertEquals("555-0100", data.getPhone());
        assertEquals("2 Side St", data.getAddress());
    }

    @Test
    public void updateProfile_overLongPhone_throwsBadRequest() {
        UserPojo user = aBareUser("acme-empf-7", "up3@acme.test");
        ProfileForm form = new ProfileForm();
        form.setPhone("1".repeat(21));

        assertThrows(BadRequestException.class, () -> flow.updateProfile(user.getId(), form));
    }

    @Test
    public void uploadPhoto_validPng_setsHasPhoto() {
        UserPojo user = aBareUser("acme-empf-8", "ph1@acme.test");

        SessionData data = flow.uploadPhoto(user.getId(), PNG_BYTES, "image/png");

        assertTrue(data.isHasPhoto());
    }

    @Test
    public void uploadPhoto_notAnImage_throwsBadRequest() {
        UserPojo user = aBareUser("acme-empf-9", "ph2@acme.test");

        assertThrows(BadRequestException.class, //
            () -> flow.uploadPhoto(user.getId(), "hello".getBytes(StandardCharsets.UTF_8), "image/png"));
    }

    @Test
    public void getPhoto_afterUpload_returnsBytesAndType() {
        UserPojo user = aBareUser("acme-empf-10", "ph3@acme.test");
        flow.uploadPhoto(user.getId(), PNG_BYTES, "image/png");

        PhotoData photo = flow.getPhoto(user.getId());

        assertArrayEquals(PNG_BYTES, photo.getBytes());
        assertEquals("image/png", photo.getContentType());
    }

    @Test
    public void getPhoto_noPhotoSet_throwsNotFound() {
        UserPojo user = aBareUser("acme-empf-11", "ph4@acme.test");

        assertThrows(NotFoundException.class, () -> flow.getPhoto(user.getId()));
    }

    @Test
    public void deletePhoto_removesIt_andGetPhotoThenFails() {
        UserPojo user = aBareUser("acme-empf-12", "ph5@acme.test");
        flow.uploadPhoto(user.getId(), PNG_BYTES, "image/png");

        SessionData data = flow.deletePhoto(user.getId());

        assertFalse(data.isHasPhoto());
        assertThrows(NotFoundException.class, () -> flow.getPhoto(user.getId()));
    }

    @Test
    public void deletePhoto_whenNoneSet_isHarmless() {
        UserPojo user = aBareUser("acme-empf-13", "ph6@acme.test");

        assertFalse(flow.deletePhoto(user.getId()).isHasPhoto());
    }

    /* Private Helper Methods */

    // an ORG_ADMIN user with an employee row but no employee_sensitive row
    private UserPojo aBareUser(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), email, pwd("s3cret!!"), UserRole.ORG_ADMIN);
        empSvc.createOrgAdminProfile(user.getId(), org.getId());
        return user;
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
