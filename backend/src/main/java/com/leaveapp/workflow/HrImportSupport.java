package com.leaveapp.workflow;

import java.util.EnumSet;
import java.util.Set;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.EmpImportRowForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.service.UserService;

// Bulk-import row validation, manager resolution, and
// EmpForm assembly for HrEmpFlow.importEmployees -- split out to keep that
// class under the size limit. No DB writes here (read-only lookups only); the
// mandatory columns are name, email and role; everything else is optional.
final class HrImportSupport {

    /* Variables */

    private static final Set<UserRole> WRITABLE_ROLES = EnumSet.of(UserRole.EMPLOYEE, UserRole.HR);

    /* Constructors */

    private HrImportSupport() {
    }

    /* Package Methods */

    // Phase-1 per-row check: name, email and role present (EMPLOYEE or HR), not a
    // duplicate email (within the file or already in the org), managerEmail
    // resolves. Throws on any failure -- the caller's per-row try/catch turns that
    // into a skip line. Returns the resolved managerId, or null if none was given.
    static Long resolveRow(UserService userSvc, Long orgId,
        String email, Set<String> seenEmails, EmpImportRowForm row) {
        if (email.isBlank()) {
            throw new BadRequestException("email is required");
        }
        FieldRules.email("email", email);
        if (!seenEmails.add(email)) {
            throw new BadRequestException("duplicate email within the file");
        }
        UserRole role = row.getRole();
        if (role == null || !WRITABLE_ROLES.contains(role)) {
            throw new BadRequestException("role must be EMPLOYEE or HR");
        }
        if (row.getName() == null || row.getName().isBlank()) {
            throw new BadRequestException("name is required");
        }
        FieldRules.text("name", row.getName(), 128);
        FieldRules.phone("phone", row.getPhone());
        FieldRules.text("employeeCode", row.getEmployeeCode(), 64);
        FieldRules.text("employeeType", row.getEmployeeType(), 32);
        FieldRules.text("designation", row.getDesignation(), 128);
        FieldRules.text("department", row.getDepartment(), 128);
        if (userSvc.existsInOrgByEmail(orgId, email)) {
            throw new ConflictException("an account with this email already exists");
        }
        return resolveManager(userSvc, orgId, row.getManagerEmail());
    }

    static String skipLine(int row, String email, String reason) {
        return "row " + row + " (" + email + "): " + reason;
    }

    static EmpForm toEmpForm(EmpImportRowForm row, String email, Long managerId) {
        EmpForm form = new EmpForm();
        form.setEmail(email);
        form.setRole(row.getRole());
        form.setEmployeeCode(row.getEmployeeCode());
        form.setName(row.getName().trim());
        form.setPhone(row.getPhone());
        form.setDateOfJoining(row.getDateOfJoining());
        form.setDateOfExit(row.getDateOfExit());
        form.setEmployeeType(row.getEmployeeType());
        form.setDesignation(row.getDesignation());
        form.setDepartment(row.getDepartment());
        form.setManagerId(managerId);
        form.setBasicSalary(row.getBasicSalary());
        return form;
    }

    /* Private Helper Methods */

    private static Long resolveManager(UserService userSvc, Long orgId, String managerEmail) {
        if (managerEmail == null || managerEmail.isBlank()) {
            return null;
        }
        UserPojo mgr = userSvc.findByOrgAndEmailOrNull(orgId, managerEmail.trim());
        if (mgr == null) {
            throw new BadRequestException("managerEmail does not resolve to a user in this organization");
        }
        return mgr.getId();
    }
}
