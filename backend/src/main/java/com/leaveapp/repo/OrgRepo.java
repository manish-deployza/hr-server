package com.leaveapp.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.OrgPojo;

public interface OrgRepo extends JpaRepository<OrgPojo, Long> {

    @Transactional(readOnly = true)
    Optional<OrgPojo> findByOrgKey(String orgKey);
}
