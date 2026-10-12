package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmpPojo;

// The Long id is the user id (EmpPojo.userId) -- employee is 1:1 with user.
public interface EmpRepo extends JpaRepository<EmpPojo, Long> {

    @Transactional(readOnly = true)
    boolean existsByManagerId(Long managerId);

    @Transactional(readOnly = true)
    List<EmpPojo> findByOrgId(Long orgId);

    // a manager's direct reports -- backed by ix_emp_mgr
    @Transactional(readOnly = true)
    List<EmpPojo> findByManagerId(Long managerId);
}
