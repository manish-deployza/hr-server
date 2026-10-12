package com.leaveapp.model.form;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leaveapp.model.enums.ApprovalAction;

import lombok.Getter;
import lombok.Setter;

// Request body for POST /api/leave-requests/{id}/action -- the single approve/reject
// endpoint used identically by the manager and HR screens. `action` is APPROVED or
// REJECTED (PENDING is a valid enum value but not a valid client action -- LeaveFlow.act
// rejects it); `comments` is optional (no current UI control sends it). An unknown
// action string is a 400 (GlobalExceptionHandler.onBadJson), same as before this was
// typed -- note this is now case-sensitive ("approved" 400s; "APPROVED" doesn't),
// where the old hand-written check tolerated any case.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaveActionForm {

    /* Variables */

    private ApprovalAction action;
    private String comments;
}
