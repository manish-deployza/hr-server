package com.leaveapp.model.form;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.leaveapp.pojo.UserRole;

import lombok.Getter;
import lombok.Setter;

// Request body for POST /api/hr/employees (create) and PATCH /api/hr/employees/{id}
// (partial update). Mirrors the frontend's create/edit payloads (leave-frontend
// js/api/hr-employees.js). ignoreUnknown so accepted-but-ignored keys
// (forcePasswordReset) and any other extras don't 400 on FAIL_ON_UNKNOWN_PROPERTIES.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmployeeForm {

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
    private UserRole role;         // required: EMPLOYEE | HR_ADMIN
    private String status;         // patch only: ignored on create (always created ACTIVE)

    @JsonDeserialize(using = PwdDeserializer.class)
    private byte[] password = new byte[0]; // required on create; optional on patch (admin reset)
}
