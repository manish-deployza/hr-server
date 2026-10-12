package com.leaveapp.model.form;


import lombok.Getter;
import lombok.Setter;

// PATCH /api/hr/employees/{id}/details -- HR/admin edit of what the employee filled in
// during onboarding. Every section is optional; a null section (or null field inside one)
// means "leave unchanged".
@Getter
@Setter
public class EmpDetailsForm {

    /* Variables */

    private String phone;
    private PersonalInfoForm personalInfo;
    private AddressForm address;
    private EmergencyContactForm emergencyContact;
    private PayIdsForm payIds;
}
