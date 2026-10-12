package com.leaveapp.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmpPhotoPojo;

// The Long id is the user id (EmpPhotoPojo.userId) -- 1:1 with employee/user.
public interface EmpPhotoRepo extends JpaRepository<EmpPhotoPojo, Long> {

    // hasPhoto without loading the blob
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(p) > 0 FROM EmpPhotoPojo p WHERE p.userId = :userId")
    boolean existsByUserId(@Param("userId") Long userId);
}
