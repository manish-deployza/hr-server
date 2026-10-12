package com.leaveapp.model.enums;

// who an approval step is routed to -- distinct from UserRole: MANAGER here means
// "the applicant's manager", not an account role.
public enum ApproverRole {
    MANAGER,
    HR
}
