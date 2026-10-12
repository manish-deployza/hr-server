package com.leaveapp.model.enums;

// see the state machine built in LeaveFlow (apply -> pending steps -> terminal).
// LAPSED is reserved for the future accrual/expiry job -- nothing sets it yet.
public enum LeaveStatus {
    APPLIED,
    PENDING_MANAGER,
    PENDING_HR,
    APPROVED,
    REJECTED,
    CANCELLED,
    LAPSED
}
