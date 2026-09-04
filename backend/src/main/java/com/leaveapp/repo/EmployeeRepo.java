package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmployeePojo;

// The Long id is the user id (EmployeePojo.userId) -- employee is 1:1 with user.
@Transactional
public interface EmployeeRepo extends JpaRepository<EmployeePojo, Long> {

    boolean existsByManagerId(Long managerId);

    List<EmployeePojo> findByOrganizationId(Long organizationId);

    // a manager's direct reports -- backed by ix_emp_mgr
    List<EmployeePojo> findByManagerId(Long managerId);
}
