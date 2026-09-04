package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveRequestApprovalPojo;

@Transactional
public interface LeaveRequestApprovalRepo extends JpaRepository<LeaveRequestApprovalPojo, Long> {

    // full approval trail for a request, in step order
    List<LeaveRequestApprovalPojo> findByLeaveRequestIdOrderByStepOrder(Long leaveRequestId);
}
