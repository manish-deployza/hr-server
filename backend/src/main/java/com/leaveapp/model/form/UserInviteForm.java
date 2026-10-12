package com.leaveapp.model.form;

import com.leaveapp.model.enums.UserRole;

import lombok.Getter;
import lombok.Setter;

// Request body for POST /api/admin/users: the only three things an org admin supplies
// when inviting someone. Everything else is filled by the user (onboarding form) or by
// HR/admin afterwards (employment fields).
@Getter
@Setter
public class UserInviteForm {

    /* Variables */

    private String name;    // required
    private String email;   // required
    private UserRole role;  // required: EMPLOYEE | HR
}
