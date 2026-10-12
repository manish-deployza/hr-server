package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// EmpService -> EmpRepo -> DB (create/read/update the HR profile row)
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.repo.EmpRepo;

// HR profile only (name, code, joining date, manager, salary). Authentication and
// role live on UserService / UserPojo. Keyed 1:1 by the user id.
@Service
public class EmpService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(EmpService.class);

    @Autowired
    private EmpRepo empRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public EmpPojo findByUserId(Long userId) {
        return empRepo.findById(userId) //
            .orElseThrow(() -> new NotFoundException("employee profile not found userId=" + userId));
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public EmpPojo findByUserIdOrNull(Long userId) {
        if (userId == null) {
            return null;
        }
        return empRepo.findById(userId).orElse(null);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public boolean existsManager(Long userId) {
        return empRepo.existsByManagerId(userId);
    }

    // A manager's direct reports (leave team views).
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<EmpPojo> findByManager(Long managerUserId) {
        return empRepo.findByManagerId(managerUserId);
    }

    // every profile row in one org -- HR employee list. Paired 1:1 with UserService.findByOrg.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<EmpPojo> findByOrg(Long orgId) {
        return empRepo.findByOrgId(orgId);
    }

    // DirectoryFlow.list's ORG/REPORTS scopes: org-scoped, optional manager/department/
    // search filters, DB-paginated and DB-sorted (via pageable's Sort) -- see
    // EmpRepo.searchDirectory for why status filtering needs an explicit join.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Page<EmpPojo> searchDirectory(Long orgId, Long managerId, String department, String search,
        Pageable pageable) {
        return empRepo.searchDirectory(orgId, managerId, department, search, pageable);
    }

    // Self-signup org creator: employeeCode / dateOfJoining stay null until HR sets
    // them (no accrual until then -- see LeaveBalanceService.ensureAnnualAccrual).
    // `name` is null (not collected at signup).
    @Transactional(rollbackFor = Exception.class)
    public EmpPojo createOrgAdminProfile(Long userId, Long orgId) {
        Instant now = Instant.now();
        EmpPojo emp = new EmpPojo();
        emp.setUserId(userId);
        emp.setOrgId(orgId);
        emp.setEmployeeType("REGULAR");
        emp.setStatus(EmpStatus.ACTIVE);
        emp.setCreatedAt(now);
        emp.setUpdatedAt(now);
        EmpPojo saved = empRepo.save(emp);
        log.info("org admin profile created userId={} orgId={}", userId, orgId);
        return saved;
    }

    // HR-created employee: the caller supplies the profile fields, the Flow has
    // already applied defaults and validated. Sibling of createOrgAdminProfile.
    @Transactional(rollbackFor = Exception.class)
    public EmpPojo createProfile(EmpPojo emp) {
        Instant now = Instant.now();
        emp.setCreatedAt(now);
        emp.setUpdatedAt(now);
        EmpPojo saved = empRepo.save(emp);
        log.info("employee profile created userId={} orgId={}", saved.getUserId(), saved.getOrgId());
        return saved;
    }

    // HR-admin PATCH of the profile fields (name/designation/... ), called from
    // HrEmpFlow.update. phone moved to EmpSensitiveService.applyContactFields (PII);
    // split out purely to keep HrEmpFlow under the class-size limit -- no extra
    // validation beyond what the inline block it replaces already did.
    @Transactional(rollbackFor = Exception.class)
    public void applyAdminFields(EmpPojo emp, String employeeCode, String name,
        String designation, String department, String employeeType, LocalDate dateOfJoining,
        LocalDate dateOfExit, BigDecimal basicSalary) {
        if (hasText(employeeCode)) {
            emp.setEmployeeCode(employeeCode.trim()); // uk_employee_org_code collision -> 500, same as create
        }
        if (name != null) {
            emp.setName(name);
        }
        if (designation != null) {
            emp.setDesignation(designation);
        }
        if (department != null) {
            emp.setDepartment(department);
        }
        if (hasText(employeeType)) {
            emp.setEmployeeType(employeeType.trim());
        }
        if (dateOfJoining != null) {
            emp.setDateOfJoining(dateOfJoining);
        }
        if (dateOfExit != null) {
            emp.setDateOfExit(dateOfExit);
        }
        if (basicSalary != null) {
            emp.setBasicSalary(basicSalary);
        }
    }

    // Directory Step 3 (org details) -- same null-means-unchanged semantics as
    // applyAdminFields, kept as a separate method so HrEmpFlow's single-create path
    // (EmpForm/persistRow) is untouched: this field is Directory-only for now,
    // matching the deliberate dual-write-path design (see
    // prompts/features/employee-directory.md). Called from DirectoryFlow for both
    // create (against a freshly-persisted row) and update.
    @Transactional(rollbackFor = Exception.class)
    public void applyDirectoryFields(EmpPojo emp, String workLocation) {
        if (workLocation != null) {
            emp.setWorkLocation(workLocation);
        }
    }

    /* Private Helper Methods */

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
