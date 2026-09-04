package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveTypeApprovalStepPojo;

@Transactional
public interface LeaveTypeApprovalStepRepo extends JpaRepository<LeaveTypeApprovalStepPojo, Long> {

    // walks the configured approval chain in order for a leave type
    List<LeaveTypeApprovalStepPojo> findByLeaveTypeIdOrderByStepOrder(Long leaveTypeId);
}
