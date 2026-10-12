package com.leaveapp.model.enums;

// Employment lifecycle on `employee` -- the account side is UserStatus.
// ONBOARDING (invited, form not submitted) -> ONBOARDED (form submitted) -> ACTIVE (first
// login after) -> ON_NOTICE -> EXITED (HR exit; also disables the account).
public enum EmpStatus {
    ONBOARDING,
    ONBOARDED,
    ACTIVE,
    ON_NOTICE,
    EXITED
}
