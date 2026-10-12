package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// OnboardingFlow -> UserService         -> UserRepo         -> DB (status PENDING -> ONBOARDED, session revoke)
// OnboardingFlow -> EmpService          -> EmpRepo          -> DB (name for the preview and notification)
// OnboardingFlow -> EmpSensitiveService -> EmpSensitiveRepo -> DB (the personal/address/emergency/pay row)
// OnboardingFlow -> NotificationService -> NotificationRepo -> DB (HR/admin heads-up on submit)
import static com.leaveapp.workflow.FieldRules.bankAccount;
import static com.leaveapp.workflow.FieldRules.birthDate;
import static com.leaveapp.workflow.FieldRules.bloodGroup;
import static com.leaveapp.workflow.FieldRules.email;
import static com.leaveapp.workflow.FieldRules.idCode;
import static com.leaveapp.workflow.FieldRules.ifsc;
import static com.leaveapp.workflow.FieldRules.isoCountry;
import static com.leaveapp.workflow.FieldRules.nz;
import static com.leaveapp.workflow.FieldRules.phone;
import static com.leaveapp.workflow.FieldRules.postalCode;
import static com.leaveapp.workflow.FieldRules.text;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.AddressData;
import com.leaveapp.model.data.EmergencyContactData;
import com.leaveapp.model.data.OnboardingData;
import com.leaveapp.model.data.PersonalInfoData;
import com.leaveapp.model.enums.BloodGroup;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.pojo.AddressPojo;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.NotificationService;
import com.leaveapp.service.UserService;

// The invited user's own onboarding form: three steps (personal details with address,
// emergency contact, pay and IDs) saved one at a time (each PUT
// replaces its step and validates it), a preview, and a final submit. Only reachable with
// the PENDING-only ROLE_ONBOARDING session (OnboardingController), which is also what
// locks the form after submit. Every method acts on the caller's own userId -- there is
// no id in the request to tamper with.
@Service
public class OnboardingFlow {

    /* Variables */

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private NotificationService notifSvc;

    /* Public Methods */

    // Also the preview: everything entered so far, unmasked (it's the caller's own data).
    @Transactional(rollbackFor = Exception.class)
    public OnboardingData get(Long userId) {
        return toData(userId, sensFor(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public OnboardingData savePersonal(Long userId, OnboardingPersonalForm form) {
        EmpSensitivePojo sens = sensFor(userId);
        sensSvc.applyContactFields(sens, nz(phone("phone", form.getPhone())), null);
        sensSvc.applyPersonalFields(sens, birthDate("dateOfBirth", form.getDateOfBirth()),
            nz(text("gender", form.getGender(), 50)), nz(isoCountry("nationality", form.getNationality())),
            nz(text("maritalStatus", form.getMaritalStatus(), 40)), nz(bloodGroup("bloodGroup", form.getBloodGroup())),
            nz(email("personalEmail", form.getPersonalEmail())));
        AddressPojo a = new AddressPojo();
        a.setAddress1(nz(text("address1", form.getAddress1(), 150)));
        a.setAddress2(nz(text("address2", form.getAddress2(), 150)));
        a.setCity(nz(text("city", form.getCity(), 100)));
        a.setLandmark(nz(text("landmark", form.getLandmark(), 150)));
        a.setPostalCode(nz(postalCode("postalCode", form.getPostalCode())));
        a.setState(nz(text("state", form.getState(), 100)));
        a.setCountry(nz(isoCountry("country", form.getCountry())));
        sensSvc.applyAddressFields(sens, a, null, null, null);
        OnboardingSupport.require("personal details", OnboardingSupport.missingPersonal(sens));
        OnboardingSupport.require("address", OnboardingSupport.missingAddress(sens));
        return toData(userId, sens);
    }

    @Transactional(rollbackFor = Exception.class)
    public OnboardingData saveEmergency(Long userId, EmergencyContactForm form) {
        EmpSensitivePojo sens = sensFor(userId);
        sensSvc.applyAddressFields(sens, new AddressPojo(), nz(text("emergencyName", form.getEmergencyName(), 128)),
            nz(text("emergencyRelationship", form.getEmergencyRelationship(), 50)),
            nz(phone("emergencyPhone", form.getEmergencyPhone())));
        OnboardingSupport.require("emergency contact", OnboardingSupport.missingEmergency(sens));
        return toData(userId, sens);
    }

    @Transactional(rollbackFor = Exception.class)
    public OnboardingData savePayIds(Long userId, PayIdsForm form) {
        EmpSensitivePojo sens = sensFor(userId);
        sensSvc.applyPayFields(sens, nz(bankAccount("bankAccount", form.getBankAccount())),
            nz(ifsc("bankIfsc", form.getBankIfsc())), nz(idCode("nationalId", form.getNationalId())),
            nz(idCode("taxId", form.getTaxId())), nz(idCode("passport", form.getPassport())));
        OnboardingSupport.require("pay and IDs", OnboardingSupport.missingPay(sens));
        return toData(userId, sens);
    }

    // Re-checks every step (a step could have been saved, then left incomplete by a later
    // bad PUT rolling back), flips the account PENDING -> ACTIVE and the employee
    // ONBOARDING -> ONBOARDED, and tells HR and the org admins so
    // they can set the manager and employment details.
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long userId) {
        EmpSensitivePojo sens = sensFor(userId);
        List<String> incomplete = new ArrayList<>();
        addIfMissing(incomplete, "personal", OnboardingSupport.missingPersonal(sens));
        addIfMissing(incomplete, "address", OnboardingSupport.missingAddress(sens));
        addIfMissing(incomplete, "emergency", OnboardingSupport.missingEmergency(sens));
        addIfMissing(incomplete, "payIds", OnboardingSupport.missingPay(sens));
        if (!incomplete.isEmpty()) {
            throw new BadRequestException("complete these steps first: " + String.join(", ", incomplete));
        }
        userSvc.activate(userId);

        UserPojo user = userSvc.findById(userId);
        EmpPojo emp = empSvc.findByUserId(userId);
        emp.setStatus(EmpStatus.ONBOARDED);
        String who = emp.getName() == null || emp.getName().isBlank() ? user.getEmail() : emp.getName();
        for (UserPojo recipient : userSvc.findHrAndAdminUsersByOrg(user.getOrgId())) {
            notifSvc.notify(recipient.getId(), NotificationType.ONBOARDING_COMPLETED,
                who + " completed onboarding",
                "Set the reporting manager and employment details for " + who + ".",
                EntityType.USER, userId);
        }
    }

    /* Private Helper Methods */

    // A row created before the sensitive module existed has none yet -- make it lazily.
    private EmpSensitivePojo sensFor(Long userId) {
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(userId);
        return sens != null ? sens : sensSvc.createEmpty(userId);
    }

    private static void addIfMissing(List<String> incomplete, String step, List<String> missing) {
        if (!missing.isEmpty()) {
            incomplete.add(step);
        }
    }

    private OnboardingData toData(Long userId, EmpSensitivePojo s) {
        UserPojo user = userSvc.findById(userId);
        EmpPojo emp = empSvc.findByUserId(userId);
        OnboardingData d = new OnboardingData();
        d.setName(emp.getName());
        d.setEmail(user.getEmail());
        d.setStatus(HrEmpMapper.statusOf(user, emp));

        d.setPersonalDone(OnboardingSupport.missingPersonal(s).isEmpty() && OnboardingSupport.missingAddress(s).isEmpty());
        d.setEmergencyDone(OnboardingSupport.missingEmergency(s).isEmpty());
        d.setPayIdsDone(OnboardingSupport.missingPay(s).isEmpty());
        d.setNextStep(!d.isPersonalDone() ? "personal" : !d.isEmergencyDone() ? "emergency"
            : !d.isPayIdsDone() ? "payIds" : "review");

        d.setPhone(s.getPhone());
        PersonalInfoData p = new PersonalInfoData();
        p.setDateOfBirth(s.getDateOfBirth());
        p.setGender(s.getGender());
        p.setNationality(s.getNationality());
        p.setMaritalStatus(s.getMaritalStatus());
        p.setBloodGroup(BloodGroup.labelOf(s.getBloodGroup()));
        p.setPersonalEmail(s.getPersonalEmail());
        d.setPersonalInfo(p);
        d.setAddress(AddressData.from(s.getAddress()));
        EmergencyContactData e = new EmergencyContactData();
        e.setEmergencyName(s.getEmergencyName());
        e.setEmergencyRelationship(s.getEmergencyRelationship());
        e.setEmergencyPhone(s.getEmergencyPhone());
        d.setEmergencyContact(e);
        d.setBankAccount(s.getBankAccount());
        d.setBankIfsc(s.getBankIfsc());
        d.setNationalId(s.getNationalId());
        d.setTaxId(s.getTaxId());
        d.setPassport(s.getPassport());
        return d;
    }
}
