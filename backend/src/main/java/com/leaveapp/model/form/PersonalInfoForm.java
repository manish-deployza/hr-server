package com.leaveapp.model.form;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

// Shared personal-info value object -- reused via @JsonUnwrapped by the onboarding
// forms rather than repeating these 6 fields loose.
@Getter
@Setter
public class PersonalInfoForm {

    /* Variables */

    private LocalDate dateOfBirth;
    private String gender;
    private String nationality;
    private String maritalStatus;
    private String bloodGroup;
    private String personalEmail;
}
