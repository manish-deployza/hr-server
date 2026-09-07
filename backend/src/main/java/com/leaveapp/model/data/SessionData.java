package com.leaveapp.model.data;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SessionData {

    /* Variables */

    private Long id;
    private String employeeCode;
    private String name;
    private String email;
    // From the linked `user` row (users.email_verified), not `employee`. Lombok's
    // isEmailVerified() getter serializes as "emailVerified".
    private boolean emailVerified;
    private String phone;
    private String address;
    private String role;
    private Long managerId;
    private String department;
    private String designation;
    private String employeeType;
    private LocalDate dateOfJoining;
    private LocalDate dateOfExit;
    private BigDecimal basicSalary;
    private String status;

    // Boxed on purpose: a primitive `boolean isManager` gets a Lombok getter
    // isManager(), which Jackson then serializes as "manager" (it strips the "is"
    // prefix from the getter name) -- not the "isManager" key the API contract needs.
    // Boxed Boolean gets getIsManager() from Lombok instead, which Jackson serializes
    // correctly as "isManager".
    private Boolean isManager;

    private String managerName;
    private String organizationName;
    private String organizationUsername;
}
