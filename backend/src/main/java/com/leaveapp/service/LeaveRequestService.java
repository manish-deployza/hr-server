package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// LeaveRequestService -> LeaveRequestRepo         -> DB (leave_request rows)
// LeaveRequestService -> LeaveRequestApprovalRepo -> DB (the request's approval decision)
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.repo.LeaveRequestApprovalRepo;
import com.leaveapp.repo.LeaveRequestRepo;

// Data access for leave requests and their approval trail. The state machine
// (which status follows which action, who may act) lives in LeaveFlow.
@Service
public class LeaveRequestService {

    /* Variables */

    @Autowired
    private LeaveRequestRepo requestRepo;

    @Autowired
    private LeaveRequestApprovalRepo approvalRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeaveRequestPojo findById(Long id) {
        return requestRepo.findById(id) //
            .orElseThrow(() -> new NotFoundException("leave request not found id=" + id));
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeaveRequestPojo findByIdOrNull(Long id) {
        return id == null ? null : requestRepo.findById(id).orElse(null);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveRequestPojo> findByUser(Long userId) {
        return requestRepo.findByUserId(userId);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveRequestPojo> findByUsers(Collection<Long> userIds) {
        return userIds.isEmpty() ? List.of() : requestRepo.findByUserIdIn(userIds);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveRequestApprovalPojo> approvalsOf(Long requestId) {
        return approvalRepo.findByLeaveRequestId(requestId);
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaveRequestPojo save(LeaveRequestPojo request) {
        return requestRepo.save(request);
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaveRequestApprovalPojo saveApproval(LeaveRequestApprovalPojo approval) {
        return approvalRepo.save(approval);
    }
}
