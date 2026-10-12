package com.leaveapp.model.data;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

// One row of GET /api/hr/employees. Leaner than SessionData: no per-row org name and
// no computed manager fields -- the HR employees screen derives the "Manager" badge
// and manager names client-side from the full list.
@Getter
@Setter
public class EmpRowData {

    /* Variables */

    private Long id;
    private String employeeCode;
    private String name;
    private String email;
    // Not a column on `employee` -- copied from the linked `user` row (users.email_verified)
    // in HrEmpFlow.toRow. Lombok's isEmailVerified() getter serializes as "emailVerified".
    private boolean emailVerified;
    private String phone;
    private String role;
    private String status;
    private Long managerId;
    private String department;
    private String designation;
    private String employeeType;
    private LocalDate dateOfJoining;
    private LocalDate dateOfExit;
    private BigDecimal basicSalary;
}
