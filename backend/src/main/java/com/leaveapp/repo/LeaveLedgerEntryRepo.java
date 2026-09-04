package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.LeaveLedgerEntryPojo;

@Transactional
public interface LeaveLedgerEntryRepo extends JpaRepository<LeaveLedgerEntryPojo, Long> {

    // current balance = SUM(amountDays) over this result set -- see database-schema.md#leave_ledger_entry
    List<LeaveLedgerEntryPojo> findByUserIdAndLeaveTypeIdAndLeaveYear(
        Long userId, Long leaveTypeId, int leaveYear);
}
