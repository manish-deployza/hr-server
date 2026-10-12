package com.leaveapp.workflow;

import java.math.BigDecimal;
import java.util.List;

import com.leaveapp.model.data.LeaveBalanceData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.service.LeaveTypeService;

// *Pojo (entity) -> *Data (API shape). Kept out of the Flow classes so they stay
// under the size limit; no logic here beyond field copying.
final class LeaveMapper {

    /* Constructors */

    private LeaveMapper() {
    }

    /* Package Methods */

    static LeaveTypeData toTypeData(LeaveTypePojo type) {
        LeaveTypeData data = new LeaveTypeData();
        data.setId(type.getId());
        data.setName(type.getName());
        data.setCode(type.getCode());
        data.setDaysPerYear(LeaveTypeService.daysPerYearLabel(type));
        data.setRequiresAdvanceNoticeDays(type.getNoticeDays());
        data.setInfoNote(type.getInfoNote());
        return data;
    }

    static LeaveBalanceData toBalanceData(LeaveTypePojo type, BigDecimal balance) {
        LeaveBalanceData data = new LeaveBalanceData();
        data.setLeaveTypeId(type.getId());
        data.setLeaveTypeCode(type.getCode());
        data.setLeaveTypeName(type.getName());
        data.setBalance(balance);
        return data;
    }

    // List rows: no approvals / reason / HR fields (the frontend drops them).
    static LeaveRequestData toSummary(LeaveRequestPojo req, EmpPojo applicant, LeaveTypePojo type) {
        LeaveRequestData data = base(req, applicant, type);
        if (req.isHrImposed()) {
            data.setIsHrImposed(true);
        }
        return data;
    }

    // Detail row: everything, including the ordered approval trail.
    static LeaveRequestData toDetail(LeaveRequestPojo req, EmpPojo applicant, LeaveTypePojo type,
        List<LeaveRequestApprovalPojo> approvals) {
        LeaveRequestData data = base(req, applicant, type);
        data.setReason(req.getReason());
        data.setHrNotes(req.getHrNotes());
        // cancelledAt/appliedAt were collapsed into updatedAt/createdAt (code review) --
        // cancelledAt is only meaningful once actually CANCELLED, a terminal status
        // nothing mutates further, so updatedAt reliably reflects when that happened.
        data.setCancelledAt(LeaveStatus.CANCELLED == req.getStatus() ? req.getUpdatedAt() : null);
        data.setIsHrImposed(req.isHrImposed() ? Boolean.TRUE : null);
        data.setApprovals(approvals.stream().map(LeaveMapper::toApproval).toList());
        return data;
    }

    /* Private Helper Methods */

    private static LeaveRequestData base(LeaveRequestPojo req, EmpPojo applicant, LeaveTypePojo type) {
        LeaveRequestData data = new LeaveRequestData();
        data.setId(req.getId());
        data.setEmployeeId(req.getUserId());
        data.setEmployeeName(applicant != null ? applicant.getName() : null);
        data.setLeaveTypeId(req.getLeaveTypeId());
        data.setLeaveTypeCode(type != null ? type.getCode() : null);
        data.setLeaveTypeName(type != null ? type.getName() : null);
        data.setStartDate(req.getStartDate());
        data.setEndDate(req.getEndDate());
        data.setNumDays(req.getNumDays());
        data.setStatus(req.getStatus());
        // applied time is created time -- see the toDetail cancelledAt comment.
        data.setAppliedAt(req.getCreatedAt());
        return data;
    }

    private static LeaveRequestData.ApprovalData toApproval(LeaveRequestApprovalPojo approval) {
        LeaveRequestData.ApprovalData data = new LeaveRequestData.ApprovalData();
        data.setApproverRole(approval.getApproverRole());
        data.setApproverEmployeeId(approval.getApproverUserId());
        data.setAction(approval.getAction());
        data.setComments(approval.getComments());
        data.setActedAt(approval.getActedAt());
        return data;
    }
}
