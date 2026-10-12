package com.leaveapp.model.data;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

// Response-side twin of PersonalInfoForm.
@Getter
@Setter
public class PersonalInfoData {

    /* Variables */

    private LocalDate dateOfBirth;
    private String gender;
    private String nationality;
    private String maritalStatus;
    private String bloodGroup;
    private String personalEmail;
}
