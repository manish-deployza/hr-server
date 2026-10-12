package com.leaveapp.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.model.enums.EmailStatus;

public interface EmailOutboxRepo extends JpaRepository<EmailOutboxPojo, Long> {

    // oldest-first, bounded by the caller's Pageable -- background jobs poll a
    // bounded batch, never an open-ended list.
    @Transactional(readOnly = true)
    @Query("SELECT e FROM EmailOutboxPojo e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<EmailOutboxPojo> findByStatusOrderByCreatedAtAsc(@Param("status") EmailStatus status, Pageable pageable);

    // EmailOutboxReaper: terminal (SENT/FAILED) rows older than the retention cutoff,
    // oldest-first, bounded by the caller's Pageable.
    @Transactional(readOnly = true)
    @Query("""
        SELECT e.id FROM EmailOutboxPojo e
        WHERE e.status IN :statuses AND e.createdAt < :cutoff
        ORDER BY e.createdAt ASC
        """)
    List<Long> findReapableIds(@Param("statuses") Collection<EmailStatus> statuses,
        @Param("cutoff") Instant cutoff, Pageable pageable);
}
