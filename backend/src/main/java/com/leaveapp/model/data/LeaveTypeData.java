package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// API shape for GET /api/leave-types and GET /api/hr/leave-types.
@Getter
@Setter
public class LeaveTypeData {

    /* Variables */

    private Long id;
    private String name;
    private String code;
    // display string ("14 days/year"), not a number -- the frontend renders it verbatim
    private String daysPerYear;
    private Integer requiresAdvanceNoticeDays;
    // Lombok's isRequiresSituationType() serializes as "requiresSituationType"
    private boolean requiresSituationType;
    private String infoNote;
}
