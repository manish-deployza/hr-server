package com.leaveapp.repo;

import java.time.Instant;
import java.time.LocalDate;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.pojo.EmpPojo;

public class EmpTestData {

    /* Public Methods */

    // userId is the user id this profile hangs off (EmpPojo has no generator).
    public static EmpPojo anEmployee(Long orgId, Long userId) {
        Instant now = Instant.now();
        EmpPojo e = new EmpPojo();
        e.setStatus(EmpStatus.ACTIVE);
        e.setUserId(userId);
        e.setOrgId(orgId);
        e.setEmployeeCode("EMP-" + userId);
        e.setName("Test Employee");
        e.setDateOfJoining(LocalDate.of(2020, 1, 1));
        e.setEmployeeType("REGULAR");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }
}
