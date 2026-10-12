package com.leaveapp.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.leaveapp.model.enums.LedgerEntryType;
import com.leaveapp.pojo.LeaveLedgerEntryPojo;

public class LeaveLedgerEntryTestData {

    /* Public Methods */

    public static LeaveLedgerEntryPojo anEntry(Long userId, Long leaveTypeId, int leaveYear) {
        LeaveLedgerEntryPojo e = new LeaveLedgerEntryPojo();
        e.setUserId(userId);
        e.setLeaveTypeId(leaveTypeId);
        e.setLeaveYear(leaveYear);
        e.setEntryType(LedgerEntryType.ACCRUAL);
        e.setAmountDays(BigDecimal.ONE);
        e.setEffectiveDate(LocalDate.of(leaveYear, 1, 1));
        e.setCreatedBy(1L);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
