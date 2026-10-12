package com.leaveapp.model.form;

import lombok.Getter;
import lombok.Setter;

// Shared emergency-contact value object (name, relationship, phone) -- reused via
// @JsonUnwrapped by the onboarding forms rather than repeating these 3 fields loose.
@Getter
@Setter
public class EmergencyContactForm {

    /* Variables */

    private String emergencyName;
    private String emergencyRelationship;
    private String emergencyPhone;
}
