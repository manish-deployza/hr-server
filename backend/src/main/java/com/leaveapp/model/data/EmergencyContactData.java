package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// Response-side twin of EmergencyContactForm.
@Getter
@Setter
public class EmergencyContactData {

    /* Variables */

    private String emergencyName;
    private String emergencyRelationship;
    private String emergencyPhone;
}
