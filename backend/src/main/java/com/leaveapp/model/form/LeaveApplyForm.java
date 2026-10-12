package com.leaveapp.model.form;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

// Request body for POST /api/leave-requests (leave-frontend js/leave-request-form.js).
// ignoreUnknown so expectedDeliveryDate -- a maternity-only client field with no
// column in this schema -- is accepted and dropped rather than 400ing.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaveApplyForm {

    /* Variables */

    private Long leaveTypeId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
}
