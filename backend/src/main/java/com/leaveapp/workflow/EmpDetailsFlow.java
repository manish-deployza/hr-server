package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// EmpDetailsFlow -> EmpSensitiveService -> EmpSensitiveRepo -> DB (personal/address/emergency/pay row)
// EmpDetailsFlow -> AuditLogService     -> AuditLogRepo     -> DB (who edited which sections)
import static com.leaveapp.workflow.FieldRules.bankAccount;
import static com.leaveapp.workflow.FieldRules.birthDate;
import static com.leaveapp.workflow.FieldRules.bloodGroup;
import static com.leaveapp.workflow.FieldRules.email;
import static com.leaveapp.workflow.FieldRules.idCode;
import static com.leaveapp.workflow.FieldRules.ifsc;
import static com.leaveapp.workflow.FieldRules.isoCountry;
import static com.leaveapp.workflow.FieldRules.phone;
import static com.leaveapp.workflow.FieldRules.postalCode;
import static com.leaveapp.workflow.FieldRules.text;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.enums.AuditAction;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.form.AddressForm;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.EmpDetailsForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.model.form.PersonalInfoForm;
import com.leaveapp.pojo.AddressPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.service.AuditLogService;
import com.leaveapp.service.EmpSensitiveService;

// HR/admin edit of what the employee filled in during onboarding. PATCH semantics (null
// = unchanged), so unlike the onboarding PUTs nothing is required. The controller has
// already authorized the target; this only writes and audits.
@Service
public class EmpDetailsFlow {

    /* Variables */

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private AuditLogService auditSvc;

    /* Public Methods */

    @Transactional(rollbackFor = Exception.class)
    public void update(Long performedBy, Long targetId, EmpDetailsForm form) {
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(targetId);
        if (sens == null) {
            sens = sensSvc.createEmpty(targetId); // account from before the sensitive module
        }
        List<String> sections = new ArrayList<>();
        if (form.getPhone() != null) {
            sensSvc.applyContactFields(sens, phone("phone", form.getPhone()), null);
            sections.add("phone");
        }
        PersonalInfoForm p = form.getPersonalInfo();
        if (p != null) {
            sensSvc.applyPersonalFields(sens, birthDate("dateOfBirth", p.getDateOfBirth()),
                text("gender", p.getGender(), 50), isoCountry("nationality", p.getNationality()),
                text("maritalStatus", p.getMaritalStatus(), 40), bloodGroup("bloodGroup", p.getBloodGroup()),
                email("personalEmail", p.getPersonalEmail()));
            sections.add("personalInfo");
        }
        AddressForm a = form.getAddress();
        if (a != null) {
            AddressPojo in = new AddressPojo();
            in.setAddress1(text("address1", a.getAddress1(), 150));
            in.setAddress2(text("address2", a.getAddress2(), 150));
            in.setCity(text("city", a.getCity(), 100));
            in.setLandmark(text("landmark", a.getLandmark(), 150));
            in.setPostalCode(postalCode("postalCode", a.getPostalCode()));
            in.setState(text("state", a.getState(), 100));
            in.setCountry(isoCountry("country", a.getCountry()));
            sensSvc.applyAddressFields(sens, in, null, null, null);
            sections.add("address");
        }
        EmergencyContactForm e = form.getEmergencyContact();
        if (e != null) {
            sensSvc.applyAddressFields(sens, new AddressPojo(), text("emergencyName", e.getEmergencyName(), 128),
                text("emergencyRelationship", e.getEmergencyRelationship(), 50),
                phone("emergencyPhone", e.getEmergencyPhone()));
            sections.add("emergencyContact");
        }
        PayIdsForm pay = form.getPayIds();
        if (pay != null) {
            sensSvc.applyPayFields(sens, bankAccount("bankAccount", pay.getBankAccount()),
                ifsc("bankIfsc", pay.getBankIfsc()), idCode("nationalId", pay.getNationalId()),
                idCode("taxId", pay.getTaxId()), idCode("passport", pay.getPassport()));
            sections.add("payIds");
        }
        if (sections.isEmpty()) {
            return;
        }
        // section names only -- never the values (bank/ID numbers stay out of audit_log)
        auditSvc.record(EntityType.EMPLOYEE, targetId, AuditAction.EMPLOYEE_DETAILS_UPDATED, performedBy,
            "{\"sections\":[\"" + String.join("\",\"", sections) + "\"]}");
    }
}
