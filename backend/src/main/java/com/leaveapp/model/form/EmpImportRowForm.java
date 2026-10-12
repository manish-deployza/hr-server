package com.leaveapp.model.form;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leaveapp.model.enums.UserRole;

import lombok.Getter;
import lombok.Setter;

// One row of POST /api/hr/employees/import (see EmpImportForm). Only name, email and
// role are mandatory; every other column is optional (HR/admin fill them in later). No password --
// the user sets their own from the invite mail. A managerId
// field, if sent, is ignored (ignoreUnknown); the manager is named by managerEmail
// instead, resolved against the caller's org the same way login/create do.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmpImportRowForm {

    /* Variables */

    private String name;             // required
    private String email;            // required
    private UserRole role;           // required: EMPLOYEE | HR
    private String employeeCode;     // blank -> generated EMP-<8 hex>
    private String phone;
    private LocalDate dateOfJoining; // absent -> today
    private LocalDate dateOfExit;
    private String employeeType;     // absent -> REGULAR
    private String designation;
    private String department;
    private String managerEmail;     // resolved to a user in the caller's org; unknown -> row skipped
    private BigDecimal basicSalary;
}
