package com.leaveapp.model.form;

import lombok.Getter;
import lombok.Setter;

// PATCH body for org settings. A field left out of the JSON stays null and is
// not changed; see OrganizationService.update.
@Getter
@Setter
public class OrgForm {

    /* Variables */

    private String name;
    private String adminName;
    private String hrNotifyEmail;
}
