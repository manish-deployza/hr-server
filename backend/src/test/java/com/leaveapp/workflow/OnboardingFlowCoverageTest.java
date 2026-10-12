package com.leaveapp.workflow;

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
import com.leaveapp.model.data.OnboardingData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.CredentialService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.NotificationService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// Gap-filler for OnboardingFlowTest: the legacy account with no sensitive row, a half-filled
// personal step, and the "who" shown to HR when the new hire has no usable name.
public class OnboardingFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private OnboardingFlow flow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private CredentialService credSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    @Autowired
    private NotificationService notifSvc;

    /* Public Methods */

    @Test
    public void get_userWithoutSensitiveRow_createsItLazilyAndReportsNothingDone() {
        UserPojo user = aBarePendingUser("acme-onbc-1", "bare1@acme.test");
        assertNull(sensSvc.findByUserIdOrNull(user.getId()));

        OnboardingData d = flow.get(user.getId());

        assertNotNull(sensSvc.findByUserIdOrNull(user.getId()));
        assertFalse(d.isPersonalDone());
        assertFalse(d.isEmergencyDone());
        assertFalse(d.isPayIdsDone());
        assertEquals("personal", d.getNextStep());
        assertEquals("bare1@acme.test", d.getEmail());
    }

    @Test
    public void submit_userWithoutSensitiveRow_listsEveryIncompleteStep() {
        UserPojo user = aBarePendingUser("acme-onbc-2", "bare2@acme.test");

        BadRequestException e = assertThrows(BadRequestException.class, () -> flow.submit(user.getId()));

        assertEquals("complete these steps first: personal, address, emergency, payIds", e.getMessage());
        assertEquals(UserStatus.PENDING, user.getStatus());
    }

    @Test
    public void get_personalFieldsDoneButAddressMissing_personalStepNotDone() {
        UserPojo user = aBarePendingUser("acme-onbc-3", "half3@acme.test");
        EmpSensitivePojo sens = sensSvc.createEmpty(user.getId());
        sensSvc.applyContactFields(sens, "+91 98765 43210", null);
        sensSvc.applyPersonalFields(sens, LocalDate.of(1995, 4, 2), "Woman", "IN", "Single", null, "me@home.test");

        OnboardingData d = flow.get(user.getId());

        assertFalse(d.isPersonalDone()); // personal complete, address still empty
        assertEquals("personal", d.getNextStep());
    }

    @Test
    public void get_addressDoneButPersonalMissing_personalStepNotDone() {
        UserPojo user = aBarePendingUser("acme-onbc-4", "half4@acme.test");
        EmpSensitivePojo sens = sensSvc.createEmpty(user.getId());
        sens.getAddress().setAddress1("12 MG Road");
        sens.getAddress().setCity("Bengaluru");
        sens.getAddress().setState("KA");
        sens.getAddress().setPostalCode("560001");
        sens.getAddress().setCountry("IN");

        assertFalse(flow.get(user.getId()).isPersonalDone());
    }

    @Test
    public void get_eachStepInTurn_advancesTheNextStep() {
        Long id = aPendingInvitee("acme-onbc-5", "steps5@acme.test", "Stella Steps");
        assertEquals("personal", flow.get(id).getNextStep());

        flow.savePersonal(id, personal());
        assertEquals("emergency", flow.get(id).getNextStep());

        flow.saveEmergency(id, emergency());
        assertEquals("payIds", flow.get(id).getNextStep());

        flow.savePayIds(id, pay());
        OnboardingData d = flow.get(id);
        assertEquals("review", d.getNextStep());
        assertTrue(d.isPersonalDone() && d.isEmergencyDone() && d.isPayIdsDone());
    }

    @Test
    public void get_payIdsDoneButEmergencyMissing_nextStepIsEmergency() {
        Long id = aPendingInvitee("acme-onbc-6", "skip6@acme.test", "Sid Skip");
        flow.savePersonal(id, personal());
        flow.savePayIds(id, pay());

        OnboardingData d = flow.get(id);

        assertTrue(d.isPayIdsDone());
        assertEquals("emergency", d.getNextStep());
    }

    @Test
    public void submit_newHireWithNoName_notificationUsesTheEmailAddress() {
        OrgPojo org = anOrg("acme-onbc-7");
        UserPojo admin = userSvc.createUser(org.getId(), "admin7@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);
        Long id = aPendingInvitee(org, "nameless7@acme.test", null);
        completeAllSteps(id);

        flow.submit(id);

        NotificationPojo n = notifSvc.listForUser(admin.getId(), true).get(0);
        assertEquals("nameless7@acme.test completed onboarding", n.getTitle());
    }

    @Test
    public void submit_newHireWithBlankName_notificationUsesTheEmailAddress() {
        OrgPojo org = anOrg("acme-onbc-8");
        UserPojo admin = userSvc.createUser(org.getId(), "admin8@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);
        Long id = aPendingInvitee(org, "blank8@acme.test", "Placeholder");
        empSvc.findByUserId(id).setName("   ");
        completeAllSteps(id);

        flow.submit(id);

        assertEquals("blank8@acme.test completed onboarding", notifSvc.listForUser(admin.getId(), true).get(0).getTitle());
    }

    @Test
    public void submit_newHireWithName_notificationUsesTheName_andReachesEveryHrAndAdmin() {
        OrgPojo org = anOrg("acme-onbc-9");
        UserPojo admin = userSvc.createUser(org.getId(), "admin9@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);
        UserPojo hr = userSvc.createUser(org.getId(), "hr9@acme.test", pwd("s3cret!!"), UserRole.HR);
        UserPojo other = userSvc.createUser(org.getId(), "emp9@acme.test", pwd("s3cret!!"), UserRole.EMPLOYEE);
        Long id = aPendingInvitee(org, "named9@acme.test", "Nora Named");
        completeAllSteps(id);

        flow.submit(id);

        assertEquals("Nora Named completed onboarding", notifSvc.listForUser(admin.getId(), true).get(0).getTitle());
        assertEquals("Nora Named completed onboarding", notifSvc.listForUser(hr.getId(), true).get(0).getTitle());
        assertTrue(notifSvc.listForUser(other.getId(), true).isEmpty());
    }

    @Test
    public void submit_whenNotPending_throwsBadRequestAndNotifiesNobody() {
        OrgPojo org = anOrg("acme-onbc-10");
        UserPojo admin = userSvc.createUser(org.getId(), "admin10@acme.test", pwd("s3cret!!"), UserRole.ORG_ADMIN);
        Long id = aPendingInvitee(org, "late10@acme.test", "Larry Late");
        completeAllSteps(id);
        userSvc.findById(id).setStatus(UserStatus.ACTIVE);

        assertThrows(BadRequestException.class, () -> flow.submit(id));
        assertTrue(notifSvc.listForUser(admin.getId(), true).isEmpty());
    }

    /* Private Helper Methods */

    private OrgPojo anOrg(String orgKey) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        return org;
    }

    // a PENDING user + employee row with no employee_sensitive row (pre-sensitive-module shape)
    private UserPojo aBarePendingUser(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), email, pwd("s3cret!!"), UserRole.EMPLOYEE, UserStatus.PENDING);
        empSvc.createOrgAdminProfile(user.getId(), org.getId());
        return user;
    }

    private Long aPendingInvitee(String orgKey, String email, String name) {
        return aPendingInvitee(anOrg(orgKey), email, name);
    }

    private Long aPendingInvitee(OrgPojo org, String email, String name) {
        EmpForm form = new EmpForm();
        form.setEmail(email);
        form.setName(name);
        form.setRole(UserRole.EMPLOYEE);
        Long id = hrEmpFlow.create(org.getId(), form);
        credSvc.setInitialPassword(id, pwd("s3cret!!"));
        return id;
    }

    private void completeAllSteps(Long id) {
        flow.savePersonal(id, personal());
        flow.saveEmergency(id, emergency());
        flow.savePayIds(id, pay());
    }

    private static OnboardingPersonalForm personal() {
        OnboardingPersonalForm f = new OnboardingPersonalForm();
        f.setPhone("+91 98765 43210");
        f.setDateOfBirth(LocalDate.of(1995, 4, 2));
        f.setGender("Woman");
        f.setNationality("in");
        f.setMaritalStatus("Single");
        f.setPersonalEmail("nia@personal.test");
        f.setAddress1("12 MG Road");
        f.setCity("Bengaluru");
        f.setState("KA");
        f.setPostalCode("560001");
        f.setCountry("IN");
        return f;
    }

    private static EmergencyContactForm emergency() {
        EmergencyContactForm f = new EmergencyContactForm();
        f.setEmergencyName("Ravi New");
        f.setEmergencyRelationship("Brother");
        f.setEmergencyPhone("+91 90000 11111");
        return f;
    }

    private static PayIdsForm pay() {
        PayIdsForm f = new PayIdsForm();
        f.setBankAccount("1234567890");
        f.setBankIfsc("HDFC0001234");
        f.setNationalId("123412341234");
        f.setTaxId("ABCDE1234F");
        return f;
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
