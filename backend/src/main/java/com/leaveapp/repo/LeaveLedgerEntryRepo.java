package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveLedgerEntryPojo;

public interface LeaveLedgerEntryRepo extends JpaRepository<LeaveLedgerEntryPojo, Long> {

    // current balance = SUM(amountDays) over this result set -- see database-schema.md#leave_ledger_entry
    @Transactional(readOnly = true)
    @Query("""
        SELECT l FROM LeaveLedgerEntryPojo l
        WHERE l.userId = :userId AND l.leaveTypeId = :leaveTypeId AND l.leaveYear = :leaveYear
        """)
    List<LeaveLedgerEntryPojo> findByUserIdAndLeaveTypeIdAndLeaveYear(
        @Param("userId") Long userId, @Param("leaveTypeId") Long leaveTypeId, @Param("leaveYear") int leaveYear);
}
