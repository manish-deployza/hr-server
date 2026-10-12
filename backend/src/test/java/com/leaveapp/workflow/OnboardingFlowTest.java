package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.OnboardingData;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.CredentialService;
import com.leaveapp.service.NotificationService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// The whole invited-user path: invite -> set password (PENDING) -> three saved steps
// (personal details with address, emergency contact, pay and IDs) -> preview -> submit
// (ONBOARDED, session revoked, HR/admin notified).
public class OnboardingFlowTest extends RepositoryTestSupport {

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
    private CredentialService credSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    @Autowired
    private NotificationService notifSvc;

    /* Public Methods */

    @Test
    public void fullPath_resumesFromNextStep_submitFlipsStatusAndNotifiesAdmin() {
        OrgPojo org = orgSvc.create("Acme", "acme-onb-1", "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        UserPojo admin = userSvc.createUser(org.getId(), "admin@acme.test", bytes("s3cret!!"), UserRole.ORG_ADMIN);
        Long id = pendingUser(org.getId(), "new@acme.test");

        OnboardingData d = flow.get(id);
        assertEquals("PENDING", d.getStatus());
        assertEquals("personal", d.getNextStep());

        d = flow.savePersonal(id, personal()); // personal details and address in one step
        assertTrue(d.isPersonalDone());
        assertEquals("Bengaluru", d.getAddress().getCity());
        assertEquals("emergency", d.getNextStep());
        d = flow.saveEmergency(id, emergency());
        assertEquals("payIds", d.getNextStep());
        d = flow.savePayIds(id, pay());
        assertEquals("review", d.getNextStep());
        assertEquals("1234567890", d.getBankAccount()); // own data, unmasked, for the preview

        flow.submit(id);

        assertEquals(UserStatus.ACTIVE, userSvc.findById(id).getStatus());
        assertEquals("ONBOARDED", flow.get(id).getStatus());
        NotificationPojo n = notifSvc.listForUser(admin.getId(), true).get(0);
        assertEquals(NotificationType.ONBOARDING_COMPLETED, n.getType());
        assertThrows(BadRequestException.class, () -> flow.submit(id)); // locked: no longer PENDING
    }

    @Test
    public void save_rejectsMissingRequiredFields_andBadCountry() {
        OrgPojo org = orgSvc.create("Acme", "acme-onb-2", "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        Long id = pendingUser(org.getId(), "bad@acme.test");

        OnboardingPersonalForm noPhone = personal();
        noPhone.setPhone(" ");
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, noPhone));

        OnboardingPersonalForm noCity = personal(); // address is part of the personal step
        noCity.setCity("");
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, noCity));

        OnboardingPersonalForm badCountry = personal();
        badCountry.setCountry("XX");
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, badCountry));

        EmergencyContactForm noRelation = emergency();
        noRelation.setEmergencyRelationship(null);
        assertThrows(BadRequestException.class, () -> flow.saveEmergency(id, noRelation));

        PayIdsForm noTax = pay();
        noTax.setTaxId("");
        assertThrows(BadRequestException.class, () -> flow.savePayIds(id, noTax));
    }

    @Test
    public void save_isStrict_badShapesAndOverLongValuesAre400NotDbErrors() {
        OrgPojo org = orgSvc.create("Acme", "acme-onb-4", "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        Long id = pendingUser(org.getId(), "strict@acme.test");

        OnboardingPersonalForm badPhone = personal();
        badPhone.setPhone("call me");
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, badPhone));

        OnboardingPersonalForm badEmail = personal();
        badEmail.setPersonalEmail("not-an-email");
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, badEmail));

        OnboardingPersonalForm futureDob = personal();
        futureDob.setDateOfBirth(LocalDate.now().plusDays(1));
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, futureDob));

        OnboardingPersonalForm longLine = personal();
        longLine.setAddress1("x".repeat(151)); // column is 150: must be a 400, not a DB error
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, longLine));

        OnboardingPersonalForm markup = personal();
        markup.setCity("<script>alert(1)</script>");
        assertThrows(BadRequestException.class, () -> flow.savePersonal(id, markup));

        PayIdsForm badIfsc = pay();
        badIfsc.setBankIfsc("NOTANIFSC");
        assertThrows(BadRequestException.class, () -> flow.savePayIds(id, badIfsc));
    }

    @Test
    public void submit_withIncompleteSteps_isRejectedAndStaysPending() {
        OrgPojo org = orgSvc.create("Acme", "acme-onb-3", "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        Long id = pendingUser(org.getId(), "half@acme.test");
        flow.savePersonal(id, personal());

        assertThrows(BadRequestException.class, () -> flow.submit(id));
        assertEquals(UserStatus.PENDING, userSvc.findById(id).getStatus());
        assertFalse(flow.get(id).isEmergencyDone());
    }

    /* Private Helper Methods */

    private Long pendingUser(Long orgId, String email) {
        UserInviteForm invite = new UserInviteForm();
        invite.setName("Nia New");
        invite.setEmail(email);
        invite.setRole(UserRole.EMPLOYEE);
        Long id = hrEmpFlow.invite(orgId, invite);
        credSvc.setInitialPassword(id, bytes("s3cret!!"));
        return id;
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

    private static byte[] bytes(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
