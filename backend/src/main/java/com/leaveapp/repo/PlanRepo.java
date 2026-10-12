package com.leaveapp.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.enums.PlanCode;
import com.leaveapp.pojo.PlanPojo;

public interface PlanRepo extends JpaRepository<PlanPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT p FROM PlanPojo p WHERE p.code = :code")
    Optional<PlanPojo> findByCode(@Param("code") PlanCode code);

    // bounded: the plan table is a small, fixed catalog (FREE/STARTER/GROWTH/ENTERPRISE),
    // same carve-out as leave_type's active-types listing.
    @Transactional(readOnly = true)
    @Query("SELECT p FROM PlanPojo p WHERE p.active = true")
    List<PlanPojo> findByActiveTrue();
}
