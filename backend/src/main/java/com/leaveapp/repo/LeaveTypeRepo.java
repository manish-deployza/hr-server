package com.leaveapp.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveTypePojo;

@Transactional
public interface LeaveTypeRepo extends JpaRepository<LeaveTypePojo, Long> {

    Optional<LeaveTypePojo> findByOrganizationIdAndCode(Long organizationId, String code);

    Optional<LeaveTypePojo> findByIdAndOrganizationId(Long id, Long organizationId);

    List<LeaveTypePojo> findByOrganizationIdAndActiveTrue(Long organizationId);

    List<LeaveTypePojo> findByOrganizationId(Long organizationId);
}
