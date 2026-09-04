package com.leaveapp.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveRequestPojo;

@Transactional
public interface LeaveRequestRepo extends JpaRepository<LeaveRequestPojo, Long> {

    // one employee's whole history (self-service list + overlap check)
    List<LeaveRequestPojo> findByUserId(Long userId);

    // manager team queue / HR company-wide view -- filtering is client-side
    List<LeaveRequestPojo> findByUserIdIn(Collection<Long> userIds);
}
