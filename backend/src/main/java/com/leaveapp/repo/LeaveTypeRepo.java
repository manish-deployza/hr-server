package com.leaveapp.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveTypePojo;

public interface LeaveTypeRepo extends JpaRepository<LeaveTypePojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT t FROM LeaveTypePojo t WHERE t.orgId = :orgId AND t.code = :code")
    Optional<LeaveTypePojo> findByOrgIdAndCode(@Param("orgId") Long orgId, @Param("code") String code);

    @Transactional(readOnly = true)
    @Query("SELECT t FROM LeaveTypePojo t WHERE t.id = :id AND t.orgId = :orgId")
    Optional<LeaveTypePojo> findByIdAndOrgId(@Param("id") Long id, @Param("orgId") Long orgId);

    @Transactional(readOnly = true)
    @Query("SELECT t FROM LeaveTypePojo t WHERE t.orgId = :orgId AND t.active = true")
    List<LeaveTypePojo> findByOrgIdAndActiveTrue(@Param("orgId") Long orgId);

    @Transactional(readOnly = true)
    @Query("SELECT t FROM LeaveTypePojo t WHERE t.orgId = :orgId")
    List<LeaveTypePojo> findByOrgId(@Param("orgId") Long orgId);
}
