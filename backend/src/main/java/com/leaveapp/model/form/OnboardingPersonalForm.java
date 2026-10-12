package com.leaveapp.model.form;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

// PUT /api/onboarding/personal -- the personal-details step: phone, personal info and the
// home address, all flat in the JSON. Declared flat (not @JsonUnwrapped): Jackson skips the
// unknown-property check on an unwrapped object, and this body must reject anything it does
// not name.
@Getter
@Setter
public class OnboardingPersonalForm {

    /* Variables */

    private String phone;
    private LocalDate dateOfBirth;
    private String gender;
    private String nationality;
    private String maritalStatus;
    private String bloodGroup;
    private String personalEmail;

    private String address1;
    private String address2;
    private String city;
    private String landmark;
    private String postalCode;
    private String state;
    private String country;
}
