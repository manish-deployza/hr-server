package com.leaveapp.model.form;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leaveapp.model.enums.UserRole;

import lombok.Getter;
import lombok.Setter;

// Request body for PATCH /api/hr/employees/{id} (partial update); also the internal
// create shape HrEmpFlow.invite and the bulk import build (no endpoint takes it for
// create any more -- see UserInviteForm). ignoreUnknown so accepted-but-ignored keys
// (forcePasswordReset) and any other extras don't 400 on FAIL_ON_UNKNOWN_PROPERTIES.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmpForm {

    /* Variables */

    private String employeeCode;   // generated EMP-<8 hex> if blank
    private String name;           // nullable
    private String email;          // required
    private String phone;          // nullable
    private LocalDate dateOfJoining; // "YYYY-MM-DD"; today if absent
    private LocalDate dateOfExit;  // nullable
    private String employeeType;   // "REGULAR" if absent
    private String designation;    // nullable
    private String department;     // nullable
    private Long managerId;        // null = none; else must be a user in the caller's org
    private BigDecimal basicSalary; // nullable
    private UserRole role;         // required: EMPLOYEE | HR
    private String status;         // patch only: ignored on create (always created INVITED)

}
