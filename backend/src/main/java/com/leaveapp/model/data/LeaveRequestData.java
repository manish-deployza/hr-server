package com.leaveapp.model.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.LeaveStatus;

import lombok.Getter;
import lombok.Setter;

// API shape for a leave request. Summary rows (list endpoints) leave `approvals`,
// `reason` and the HR fields null; the detail endpoint fills everything.
// `employeeId` is the applicant's user id (same id space as SessionData.id).
@Getter
@Setter
public class LeaveRequestData {

    /* Variables */

    private Long id;
    private Long employeeId;
    private String employeeName;
    private Long leaveTypeId;
    private String leaveTypeCode;
    private String leaveTypeName;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal numDays;
    private LeaveStatus status;
    private String reason;
    // boxed: null unless this really is an HR-imposed LOP row, and Lombok's
    // getIsHrImposed() then serializes as "isHrImposed" (see SessionData.isManager)
    private Boolean isHrImposed;
    private String hrNotes;
    private Instant appliedAt;
    private Instant cancelledAt;
    private List<ApprovalData> approvals;

    /* Nested Types */

    @Getter
    @Setter
    public static class ApprovalData {
        private ApproverRole approverRole;
        private Long approverEmployeeId; // the acting user's id, null while PENDING
        private ApprovalAction action;
        private String comments;
        private Instant actedAt;
    }
}
