package com.leaveapp.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmpSensitivePojo;

// The Long id is the user id (EmpSensitivePojo.userId) -- 1:1 with employee/user,
// same pattern as EmpRepo.
public interface EmpSensitiveRepo extends JpaRepository<EmpSensitivePojo, Long> {

    // Batch lookup for DirectoryFlow.list -- called with just the current page's ids
    // (already bounded to page size, <= 200), not the whole org.
    @Transactional(readOnly = true)
    @Query("SELECT s FROM EmpSensitivePojo s WHERE s.userId IN :userIds")
    List<EmpSensitivePojo> findByUserIdIn(@Param("userIds") Collection<Long> userIds);
}
