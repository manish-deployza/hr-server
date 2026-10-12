package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.AuditAction;
import com.leaveapp.model.enums.BloodGroup;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.AddressForm;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.EmpDetailsForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.model.form.PersonalInfoForm;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.AuditLogPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.repo.AuditLogRepo;
import com.leaveapp.repo.EmpSensitiveRepo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;

// Gap-filler for EmpDetailsFlowTest: each section on its own and all together, the
// no-op and legacy-row paths, the normalisation each rule applies, and a rejected value
// in every section.
public class EmpDetailsFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmpDetailsFlow flow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private EmpSensitiveRepo sensRepo;

    @Autowired
    private AuditLogRepo auditRepo;

    /* Public Methods */

    @Test
    public void update_emptyForm_changesNothingAndWritesNoAuditRow() {
        Long targetId = anEmployee("acme-detc-1", "e1@acme.test");

        flow.update(7L, targetId, new EmpDetailsForm());

        assertEquals(0, auditsFor(targetId));
    }

    @Test
    public void update_employeeWithoutSensitiveRow_createsItFirst() {
        Long targetId = anEmployee("acme-detc-2", "e2@acme.test");
        sensRepo.deleteById(targetId); // an account from before the sensitive module
        sensRepo.flush();
        assertNull(sensSvc.findByUserIdOrNull(targetId));
        EmpDetailsForm form = new EmpDetailsForm();
        form.setPhone("+91 90000 11111");

        flow.update(7L, targetId, form);

        assertEquals("+91 90000 11111", sensSvc.findByUserIdOrNull(targetId).getPhone());
    }

    @Test
    public void update_blankPhone_clearsTheStoredPhone() {
        Long targetId = anEmployee("acme-detc-3", "e3@acme.test");
        EmpDetailsForm set = new EmpDetailsForm();
        set.setPhone("+91 90000 11111");
        flow.update(7L, targetId, set);

        EmpDetailsForm clear = new EmpDetailsForm();
        clear.setPhone("   ");
        flow.update(7L, targetId, clear);

        assertEquals("", sensSvc.findByUserIdOrNull(targetId).getPhone());
    }

    @Test
    public void update_allSections_writesNormalisedValuesAndAuditsEverySectionName() {
        Long targetId = anEmployee("acme-detc-4", "e4@acme.test");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setPhone(" +91 90000 22222 ");
        form.setPersonalInfo(personal());
        form.setAddress(address());
        form.setEmergencyContact(emergency());
        form.setPayIds(pay());

        flow.update(7L, targetId, form);

        EmpSensitivePojo s = sensSvc.findByUserIdOrNull(targetId);
        assertEquals("+91 90000 22222", s.getPhone());
        assertEquals(LocalDate.of(1992, 3, 4), s.getDateOfBirth());
        assertEquals("Woman", s.getGender());
        assertEquals("IN", s.getNationality());
        assertEquals("Married", s.getMaritalStatus());
        assertEquals(BloodGroup.O_POS, s.getBloodGroup());
        assertEquals("me@home.test", s.getPersonalEmail());
        assertEquals("12 MG Road", s.getAddress().getAddress1());
        assertEquals("Flat 2", s.getAddress().getAddress2());
        assertEquals("Bengaluru", s.getAddress().getCity());
        assertEquals("Metro", s.getAddress().getLandmark());
        assertEquals("560001", s.getAddress().getPostalCode());
        assertEquals("KA", s.getAddress().getState());
        assertEquals("IN", s.getAddress().getCountry());
        assertEquals("Ravi", s.getEmergencyName());
        assertEquals("Brother", s.getEmergencyRelationship());
        assertEquals("+91 90000 33333", s.getEmergencyPhone());
        assertEquals("9988776655", s.getBankAccount());
        assertEquals("HDFC0001234", s.getBankIfsc());
        assertEquals("ABCDE1234F", s.getNationalId());
        assertEquals("TAX 123", s.getTaxId());
        assertEquals("P1234567", s.getPassport());

        AuditLogPojo audit = lastAudit(targetId);
        assertEquals("{\"sections\":[\"phone\",\"personalInfo\",\"address\",\"emergencyContact\",\"payIds\"]}",
            audit.getAfterJson());
    }

    @Test
    public void update_personalInfoOnly_leavesOtherSectionsAlone() {
        Long targetId = anEmployee("acme-detc-5", "e5@acme.test");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setPersonalInfo(personal());

        flow.update(7L, targetId, form);

        EmpSensitivePojo s = sensSvc.findByUserIdOrNull(targetId);
        assertEquals("Woman", s.getGender());
        assertNull(s.getAddress().getCity());
        assertNull(s.getEmergencyName());
        assertNull(s.getBankAccount());
        assertEquals(1, auditsFor(targetId));
        assertTrue(lastAudit(targetId).getAfterJson().contains("personalInfo"));
        assertFalse(lastAudit(targetId).getAfterJson().contains("address"));
    }

    @Test
    public void update_emptyPersonalInfoSection_isStillAnAuditedSectionButChangesNothing() {
        Long targetId = anEmployee("acme-detc-6", "e6@acme.test");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setPersonalInfo(new PersonalInfoForm());

        flow.update(7L, targetId, form);

        assertNull(sensSvc.findByUserIdOrNull(targetId).getGender());
        assertEquals(1, auditsFor(targetId));
    }

    @Test
    public void update_addressOnly_countryUpperCasedAndOthersUntouched() {
        Long targetId = anEmployee("acme-detc-7", "e7@acme.test");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setAddress(address());
        form.getAddress().setCountry("in");

        flow.update(7L, targetId, form);

        EmpSensitivePojo s = sensSvc.findByUserIdOrNull(targetId);
        assertEquals("IN", s.getAddress().getCountry());
        assertNull(s.getGender());
        assertNull(s.getEmergencyName());
    }

    @Test
    public void update_emergencyContactOnly_isWrittenAndAudited() {
        Long targetId = anEmployee("acme-detc-8", "e8@acme.test");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setEmergencyContact(emergency());

        flow.update(7L, targetId, form);

        EmpSensitivePojo s = sensSvc.findByUserIdOrNull(targetId);
        assertEquals("Ravi", s.getEmergencyName());
        assertEquals("+91 90000 33333", s.getEmergencyPhone());
        assertNull(s.getAddress().getCity());
        assertTrue(lastAudit(targetId).getAfterJson().contains("emergencyContact"));
    }

    @Test
    public void update_payIdsOnly_isWrittenUpperCasedAndAudited() {
        Long targetId = anEmployee("acme-detc-9", "e9@acme.test");
        EmpDetailsForm form = new EmpDetailsForm();
        form.setPayIds(pay());
        form.getPayIds().setBankIfsc("hdfc0001234");
        form.getPayIds().setPassport("p1234567");

        flow.update(7L, targetId, form);

        EmpSensitivePojo s = sensSvc.findByUserIdOrNull(targetId);
        assertEquals("HDFC0001234", s.getBankIfsc());
        assertEquals("P1234567", s.getPassport());
        assertTrue(lastAudit(targetId).getAfterJson().contains("payIds"));
    }

    @Test
    public void update_invalidValueInAnySection_throwsBadRequestAndAuditsNothing() {
        Long targetId = anEmployee("acme-detc-10", "e10@acme.test");

        EmpDetailsForm badPhone = new EmpDetailsForm();
        badPhone.setPhone("12345");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badPhone));

        EmpDetailsForm badDob = new EmpDetailsForm();
        badDob.setPersonalInfo(new PersonalInfoForm());
        badDob.getPersonalInfo().setDateOfBirth(LocalDate.now().plusDays(1));
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badDob));

        EmpDetailsForm badBlood = new EmpDetailsForm();
        badBlood.setPersonalInfo(new PersonalInfoForm());
        badBlood.getPersonalInfo().setBloodGroup("Z+");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badBlood));

        EmpDetailsForm badEmail = new EmpDetailsForm();
        badEmail.setPersonalInfo(new PersonalInfoForm());
        badEmail.getPersonalInfo().setPersonalEmail("nope");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badEmail));

        EmpDetailsForm badPostal = new EmpDetailsForm();
        badPostal.setAddress(new AddressForm());
        badPostal.getAddress().setPostalCode("5");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badPostal));

        EmpDetailsForm markup = new EmpDetailsForm();
        markup.setAddress(new AddressForm());
        markup.getAddress().setCity("<b>x</b>");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, markup));

        EmpDetailsForm badEmergencyPhone = new EmpDetailsForm();
        badEmergencyPhone.setEmergencyContact(new EmergencyContactForm());
        badEmergencyPhone.getEmergencyContact().setEmergencyPhone("abc");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badEmergencyPhone));

        EmpDetailsForm longName = new EmpDetailsForm();
        longName.setEmergencyContact(new EmergencyContactForm());
        longName.getEmergencyContact().setEmergencyName("x".repeat(129));
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, longName));

        EmpDetailsForm badAccount = new EmpDetailsForm();
        badAccount.setPayIds(new PayIdsForm());
        badAccount.getPayIds().setBankAccount("12 34");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badAccount));

        EmpDetailsForm badId = new EmpDetailsForm();
        badId.setPayIds(new PayIdsForm());
        badId.getPayIds().setTaxId("AB");
        assertThrows(BadRequestException.class, () -> flow.update(7L, targetId, badId));

        assertEquals(0, auditsFor(targetId));
    }

    /* Private Helper Methods */

    private Long anEmployee(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        UserInviteForm invite = new UserInviteForm();
        invite.setName("Eve Employee");
        invite.setEmail(email);
        invite.setRole(UserRole.EMPLOYEE);
        return hrEmpFlow.invite(org.getId(), invite);
    }

    private long auditsFor(Long targetId) {
        return auditRepo.findAll().stream().filter(a -> a.getEntityId().equals(targetId) && AuditAction.EMPLOYEE_DETAILS_UPDATED == a.getAction()).count();
    }

    private AuditLogPojo lastAudit(Long targetId) {
        AuditLogPojo audit = auditRepo.findAll().stream().filter(a -> a.getEntityId().equals(targetId) && AuditAction.EMPLOYEE_DETAILS_UPDATED == a.getAction())
            .reduce((first, second) -> second).orElse(null);
        assertNotNull(audit);
        return audit;
    }

    private static PersonalInfoForm personal() {
        PersonalInfoForm p = new PersonalInfoForm();
        p.setDateOfBirth(LocalDate.of(1992, 3, 4));
        p.setGender("Woman");
        p.setNationality("in");
        p.setMaritalStatus("Married");
        p.setBloodGroup("o+");
        p.setPersonalEmail("me@home.test");
        return p;
    }

    private static AddressForm address() {
        AddressForm a = new AddressForm();
        a.setAddress1("12 MG Road");
        a.setAddress2("Flat 2");
        a.setCity("Bengaluru");
        a.setLandmark("Metro");
        a.setPostalCode("560001");
        a.setState("KA");
        a.setCountry("IN");
        return a;
    }

    private static EmergencyContactForm emergency() {
        EmergencyContactForm e = new EmergencyContactForm();
        e.setEmergencyName("Ravi");
        e.setEmergencyRelationship("Brother");
        e.setEmergencyPhone("+91 90000 33333");
        return e;
    }

    private static PayIdsForm pay() {
        PayIdsForm p = new PayIdsForm();
        p.setBankAccount("9988776655");
        p.setBankIfsc("HDFC0001234");
        p.setNationalId("abcde1234f");
        p.setTaxId("tax 123");
        p.setPassport("P1234567");
        return p;
    }
}
