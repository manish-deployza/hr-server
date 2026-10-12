package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.pojo.LeaveTypePojo;

public class LeaveTypeTestData {

    /* Public Methods */

    public static LeaveTypePojo aLeaveType(String code) {
        return aLeaveType(1L, code);
    }

    public static LeaveTypePojo aLeaveType(Long orgId, String code) {
        Instant now = Instant.now();
        LeaveTypePojo t = new LeaveTypePojo();
        t.setOrgId(orgId);
        t.setCode(code);
        t.setName("Test Leave Type");
        t.setApproverRole(ApproverRole.MANAGER);
        t.setAccrualStrategy(AccrualStrategy.NONE);
        t.setEncashable(false);
        t.setActive(true);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }
}
