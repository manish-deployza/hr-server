package com.leaveapp.model.data;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import lombok.Getter;
import lombok.Setter;

// One row of GET /api/directory. P0/P1 fields, plus basicSalary and the Steps 4/5/6/9
// P2-P4 fields below -- see prompts/features/employee-directory.md, "Sensitive Module
// (Steps 4/5/6/9)" for why those exist here now, and its Access Control section for who
// actually receives them (DirectoryFlow.list only populates them when the caller is
// ORG_ADMIN -- every other caller gets this class with those fields left null, not just
// omitted from a stricter subclass). Never dateOfExit, role, emailVerified. Deliberately
// leaner than EmpRowData, which stays HR-only via GET /api/hr/employees.
//
// bankAccount/nationalId/taxId/passport NEVER carry the real value -- DirectoryFlow.toData
// always masks them ("•••• 1234" or null) before assigning, for every caller including
// ORG_ADMIN. There is no reveal endpoint in this build.
@Getter
@Setter
public class DirectoryData {

    /* Variables */

    private Long id;
    private String employeeCode;
    private String name;
    private String email;
    private String phone;
    private String designation;
    private String department;
    private String employeeType;
    private String status;
    private LocalDate dateOfJoining;
    private Long managerId;

    // Step 3 -- Org details
    private String workLocation;

    // Step 6 -- Pay & IDs. bankAccount/nationalId/taxId/passport are always the masked
    // form ("•••• 1234") or null -- see the class comment.
    private BigDecimal basicSalary;
    private String bankAccount;
    private String bankIfsc;
    private String nationalId;
    private String taxId;
    private String passport;

    // Step 4 -- Personal
    @JsonUnwrapped
    private PersonalInfoData personalInfo = new PersonalInfoData();

    // Step 5 -- Address + emergency contact
    @JsonUnwrapped
    private AddressData address = new AddressData();

    @JsonUnwrapped
    private EmergencyContactData emergencyContact = new EmergencyContactData();
}
