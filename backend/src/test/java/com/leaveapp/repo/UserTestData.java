package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;

public class UserTestData {

    /* Public Methods */

    public static UserPojo aUser(Long orgId, String email) {
        Instant now = Instant.now();
        UserPojo u = new UserPojo();
        u.setOrgId(orgId);
        u.setEmail(email);
        u.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
        u.setEmailVerified(false);
        u.setRole(UserRole.EMPLOYEE);
        u.setStatus(UserStatus.ACTIVE);
        u.setFailedAttempts(0);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return u;
    }
}
