package com.leaveapp.model.form;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

// Request body for POST / PATCH /api/hr/leave-types (leave-frontend js/api/hr-leave-types.js).
// PATCH semantics: a field left out of the JSON stays null and is not changed.
// `daysPerYear` arrives as the display string the form shows ("14 days/year" or
// "14"); the numeric entitlement is parsed out of it.
// `code` is ignored on POST -- create derives it from `name` (LeaveTypeService);
// it's still honored on PATCH to rename an existing type's code.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaveTypeForm {

    /* Variables */

    private String name;
    private String code;
    private String daysPerYear;
    private Integer requiresAdvanceNoticeDays;
    private String infoNote;
}
