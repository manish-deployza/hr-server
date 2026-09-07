package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// Response for POST /api/leave-requests -- replaces the old Map.of("id", ..., "status", ...).
@Getter
@Setter
public class LeaveApplyData {

    /* Variables */

    private Long id;
    private String status;
}
