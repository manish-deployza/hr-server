package com.leaveapp.pojo;

// EXITED blocks login (UserService.verifyLogin) and any existing session.
public enum UserStatus {
    ACTIVE,
    ON_NOTICE,
    EXITED
}
