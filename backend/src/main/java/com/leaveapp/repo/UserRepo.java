package com.leaveapp.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.UserPojo;

@Transactional
public interface UserRepo extends JpaRepository<UserPojo, Long> {

    Optional<UserPojo> findByOrganizationIdAndEmail(Long organizationId, String email);

    boolean existsByOrganizationIdAndEmail(Long organizationId, String email);

    boolean existsByIdAndOrganizationId(Long id, Long organizationId);

    List<UserPojo> findByOrganizationId(Long organizationId);
}
