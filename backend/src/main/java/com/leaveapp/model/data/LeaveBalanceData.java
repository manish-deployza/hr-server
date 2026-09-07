package com.leaveapp.model.data;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

// One row of GET /api/leave-balances/me (and the per-employee `balances` array
// embedded in GET /api/manager/team). balance = SUM(leave_ledger_entry.amount_days)
// for this user + type + current calendar year.
@Getter
@Setter
public class LeaveBalanceData {

    /* Variables */

    private Long leaveTypeId;
    private String leaveTypeCode;
    private String leaveTypeName;
    private BigDecimal balance;
}
