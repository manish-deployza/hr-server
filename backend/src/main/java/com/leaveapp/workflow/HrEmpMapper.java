package com.leaveapp.workflow;

import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.UserPojo;

// (UserPojo + EmpPojo + EmpSensitivePojo) -> EmpRowData. Kept out of HrEmpFlow so it
// stays under the size limit; no logic here beyond field copying.
final class HrEmpMapper {

    /* Constructors */

    private HrEmpMapper() {
    }

    /* Package Methods */

    // The one `status` the API shows, unchanged from before the account/employment split:
    // the account state while the user is still INVITED/PENDING, the employment state
    // (ONBOARDED/ACTIVE/ON_NOTICE/EXITED) after that.
    static String statusOf(UserPojo user, EmpPojo emp) {
        boolean preOnboarding = UserStatus.INVITED == user.getStatus() || UserStatus.PENDING == user.getStatus();
        return preOnboarding ? user.getStatus().name() : emp.getStatus().name();
    }

    // sens is null for a row created before the sensitive module existed, or not yet
    // batch-fetched by the caller -- phone just stays null, not an error.
    static EmpRowData toRow(UserPojo user, EmpPojo emp, EmpSensitivePojo sens) {
        EmpRowData row = new EmpRowData();
        row.setId(user.getId());
        row.setEmail(user.getEmail());
        row.setEmailVerified(user.isEmailVerified()); // verified-or-not lives on `user`, not `employee`
        row.setRole(user.getRole().name());
        row.setStatus(statusOf(user, emp));
        row.setEmployeeCode(emp.getEmployeeCode());
        row.setName(emp.getName());
        row.setPhone(sens != null ? sens.getPhone() : null);
        row.setManagerId(emp.getManagerId());
        row.setDepartment(emp.getDepartment());
        row.setDesignation(emp.getDesignation());
        row.setEmployeeType(emp.getEmployeeType());
        row.setDateOfJoining(emp.getDateOfJoining());
        row.setDateOfExit(emp.getDateOfExit());
        row.setBasicSalary(emp.getBasicSalary());
        return row;
    }
}
