package com.leaveapp.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveRequestPojo;

public interface LeaveRequestRepo extends JpaRepository<LeaveRequestPojo, Long> {

    // one employee's whole history (self-service list + overlap check)
    @Transactional(readOnly = true)
    @Query("SELECT r FROM LeaveRequestPojo r WHERE r.userId = :userId")
    List<LeaveRequestPojo> findByUserId(@Param("userId") Long userId);

    // manager team queue / HR company-wide view -- filtering is client-side
    @Transactional(readOnly = true)
    @Query("SELECT r FROM LeaveRequestPojo r WHERE r.userId IN :userIds")
    List<LeaveRequestPojo> findByUserIdIn(@Param("userIds") Collection<Long> userIds);
}
