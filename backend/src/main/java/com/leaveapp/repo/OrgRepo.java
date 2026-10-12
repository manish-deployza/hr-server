package com.leaveapp.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.OrgPojo;

public interface OrgRepo extends JpaRepository<OrgPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT o FROM OrgPojo o WHERE o.orgKey = :orgKey")
    Optional<OrgPojo> findByOrgKey(@Param("orgKey") String orgKey);
}
