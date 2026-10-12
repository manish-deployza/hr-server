package com.leaveapp.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.enums.SubscriptionStatus;
import com.leaveapp.pojo.OrgSubscriptionPojo;

public interface OrgSubscriptionRepo extends JpaRepository<OrgSubscriptionPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT s FROM OrgSubscriptionPojo s WHERE s.orgId = :orgId")
    Optional<OrgSubscriptionPojo> findByOrgId(@Param("orgId") Long orgId);

    @Transactional(readOnly = true)
    @Query("SELECT s FROM OrgSubscriptionPojo s WHERE s.razorpaySubscriptionId = :rzpId")
    Optional<OrgSubscriptionPojo> findByRazorpaySubscriptionId(@Param("rzpId") String rzpId);

    // Catch-all for a missed webhook -- oldest-updated-first, bounded by the caller's
    // Pageable (SubscriptionScheduler.reconcile polls a fixed batch, never an open list).
    @Transactional(readOnly = true)
    @Query("""
        SELECT s FROM OrgSubscriptionPojo s
        WHERE s.razorpaySubscriptionId IS NOT NULL AND s.status IN :statuses
        ORDER BY s.updatedAt ASC
        """)
    List<OrgSubscriptionPojo> findReconcilable(@Param("statuses") Collection<SubscriptionStatus> statuses,
        Pageable pageable);

    // Rows whose grace window has lapsed -- oldest-graceUntil-first, bounded batch.
    @Transactional(readOnly = true)
    @Query("""
        SELECT s FROM OrgSubscriptionPojo s
        WHERE s.status = com.leaveapp.model.enums.SubscriptionStatus.GRACE AND s.graceUntil < :now
        ORDER BY s.graceUntil ASC
        """)
    List<OrgSubscriptionPojo> findGraceExpired(@Param("now") Instant now, Pageable pageable);
}
