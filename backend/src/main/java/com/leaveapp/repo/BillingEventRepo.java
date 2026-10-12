package com.leaveapp.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.BillingEventPojo;

public interface BillingEventRepo extends JpaRepository<BillingEventPojo, Long> {

    // Not used as a check-then-insert guard (that would itself race) -- the unique
    // constraint on razorpayEventId is the actual idempotency gate; this exists only
    // for a diagnostics/lookup call site should one ever need it.
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(e) > 0 FROM BillingEventPojo e WHERE e.razorpayEventId = :eventId")
    boolean existsByRazorpayEventId(@Param("eventId") String eventId);
}
