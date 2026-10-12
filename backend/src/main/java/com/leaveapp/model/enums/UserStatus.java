package com.leaveapp.model.enums;

// Account (auth) state only -- employment state is EmpStatus on `employee`.
// INVITED (mail sent, no password yet) -> PENDING (password set, onboarding form
// unfinished; session limited to /api/onboarding/**) -> ACTIVE (form submitted).
// DISABLED blocks login (CredentialService.verifyLogin) and any existing session; set
// when HR exits the employee.
public enum UserStatus {
    INVITED,
    PENDING,
    ACTIVE,
    DISABLED
}
