package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveRequestApprovalPojo;

public interface LeaveRequestApprovalRepo extends JpaRepository<LeaveRequestApprovalPojo, Long> {

    // the one approval decision for a request (see uk_lra_request)
    @Transactional(readOnly = true)
    @Query("SELECT a FROM LeaveRequestApprovalPojo a WHERE a.leaveRequestId = :leaveRequestId")
    List<LeaveRequestApprovalPojo> findByLeaveRequestId(@Param("leaveRequestId") Long leaveRequestId);
}
