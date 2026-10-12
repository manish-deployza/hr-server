package com.leaveapp.model.enums;

// see prompts/features/database-schema.md#leave_type and leave-policy-engine.md.
// Only ANNUAL_PRORATA is implemented today (LeaveTypeService, LeaveBalanceService);
// the rest are reserved for the leave-policy engine.
public enum AccrualStrategy {
    ANNUAL_PRORATA,
    PERIODIC,
    EVENT_BASED,
    NONE
}
