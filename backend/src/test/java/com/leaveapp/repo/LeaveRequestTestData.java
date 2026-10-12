package com.leaveapp.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.model.enums.LeaveStatus;

public class LeaveRequestTestData {

    /* Public Methods */

    public static LeaveRequestPojo aRequest(Long userId, Long leaveTypeId, LeaveStatus status) {
        Instant now = Instant.now();
        LeaveRequestPojo r = new LeaveRequestPojo();
        r.setUserId(userId);
        r.setLeaveTypeId(leaveTypeId);
        r.setStartDate(LocalDate.of(2026, 1, 10));
        r.setEndDate(LocalDate.of(2026, 1, 10));
        r.setNumDays(BigDecimal.ONE);
        r.setStatus(status);
        r.setHrImposed(false);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }
}
