package com.leaveapp.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.OrganizationPojo;

@Transactional
public interface OrganizationRepo extends JpaRepository<OrganizationPojo, Long> {

    Optional<OrganizationPojo> findByUsername(String username);
}
