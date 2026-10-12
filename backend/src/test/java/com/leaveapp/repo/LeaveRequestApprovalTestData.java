package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;

public class LeaveRequestApprovalTestData {

    /* Public Methods */

    public static LeaveRequestApprovalPojo anApproval(Long leaveRequestId) {
        LeaveRequestApprovalPojo a = new LeaveRequestApprovalPojo();
        a.setLeaveRequestId(leaveRequestId);
        a.setApproverRole(ApproverRole.MANAGER);
        a.setAction(ApprovalAction.PENDING);
        a.setCreatedAt(Instant.now());
        return a;
    }
}
