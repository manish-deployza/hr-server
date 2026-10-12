package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.UserPojo;

// Profile-only table (1:1 with user, PK = user_id). Auth columns are covered by
// UserRepoTest. H2 test schema has no FK constraints (no @ManyToOne anywhere), so a
// standalone user row isn't needed here -- an arbitrary user_id is fine.
public class EmpRepoTest extends RepositoryTestSupport {

    /* Variables */

    private static final Long ORG_1 = 1L;
    private static final Long ORG_2 = 2L;
    private static final AtomicLong UID = new AtomicLong(1000);

    @Autowired
    private EmpRepo employeeRepo;

    @Autowired
    private UserRepo userRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        EmpPojo saved = employeeRepo.save(anEmployee(ORG_1));
        assertNotNull(saved.getUserId());
    }

    @Test
    public void existsByManagerId_true() {
        EmpPojo mgr = employeeRepo.save(anEmployee(ORG_1));
        EmpPojo report = anEmployee(ORG_1);
        report.setManagerId(mgr.getUserId());
        employeeRepo.save(report);

        assertTrue(employeeRepo.existsByManagerId(mgr.getUserId()));
    }

    @Test
    public void existsByManagerId_false() {
        assertFalse(employeeRepo.existsByManagerId(999999L));
    }

    @Test
    public void findByOrgId_excludesOtherOrg() {
        employeeRepo.save(anEmployee(ORG_1));
        employeeRepo.save(anEmployee(ORG_2));

        List<EmpPojo> found = employeeRepo.findByOrgId(ORG_1);

        assertEquals(1, found.size());
        assertEquals(ORG_1, found.get(0).getOrgId());
    }

    @Test
    public void findByManagerId_returnsReports() {
        EmpPojo mgr = employeeRepo.save(anEmployee(ORG_1));
        EmpPojo report = anEmployee(ORG_1);
        report.setManagerId(mgr.getUserId());
        employeeRepo.save(report);
        employeeRepo.save(anEmployee(ORG_1));

        List<EmpPojo> found = employeeRepo.findByManagerId(mgr.getUserId());

        assertEquals(1, found.size());
        assertEquals(report.getUserId(), found.get(0).getUserId());
    }

    @Test
    public void save_nullOrgId_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setOrgId(null);
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    // Code is set by HR later -- null until then, no placeholder.
    @Test
    public void save_nullEmployeeCode_succeeds() {
        EmpPojo e = anEmployee(ORG_1);
        e.setEmployeeCode(null);
        assertNotNull(employeeRepo.saveAndFlush(e).getUserId());
    }

    // Name is optional (invited users have none yet), so null is allowed.
    @Test
    public void save_nullName_succeeds() {
        EmpPojo e = anEmployee(ORG_1);
        e.setName(null);
        EmpPojo saved = employeeRepo.saveAndFlush(e);
        assertNotNull(saved.getUserId());
    }

    // Joining date is set by HR later; accrual waits for it.
    @Test
    public void save_nullDateOfJoining_succeeds() {
        EmpPojo e = anEmployee(ORG_1);
        e.setDateOfJoining(null);
        assertNotNull(employeeRepo.saveAndFlush(e).getUserId());
    }

    @Test
    public void save_nullStatus_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setStatus(null);
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullEmployeeType_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setEmployeeType(null);
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    @Test
    public void save_duplicateEmployeeCodeSameOrg_throws() {
        EmpPojo a = anEmployee(ORG_1);
        a.setEmployeeCode("EMP-DUP");
        employeeRepo.saveAndFlush(a);

        EmpPojo b = anEmployee(ORG_1);
        b.setEmployeeCode("EMP-DUP");
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(b));
    }

    @Test
    public void save_sameEmployeeCodeDifferentOrg_succeeds() {
        EmpPojo a = anEmployee(ORG_1);
        a.setEmployeeCode("EMP-CROSSORG");
        employeeRepo.saveAndFlush(a);

        EmpPojo b = anEmployee(ORG_2);
        b.setEmployeeCode("EMP-CROSSORG");
        EmpPojo saved = employeeRepo.saveAndFlush(b);
        assertEquals("EMP-CROSSORG", saved.getEmployeeCode());
    }

    @Test
    public void findByOrgId_unknownOrg_empty() {
        assertTrue(employeeRepo.findByOrgId(777L).isEmpty());
    }

    @Test
    public void findByManagerId_noReports_empty() {
        EmpPojo loner = employeeRepo.save(anEmployee(ORG_1));
        assertTrue(employeeRepo.findByManagerId(loner.getUserId()).isEmpty());
    }

    // Boundary: employee_code is VARCHAR(64) -- exactly 64 chars fits...
    @Test
    public void save_employeeCodeAtMaxLength_succeeds() {
        EmpPojo e = anEmployee(ORG_1);
        e.setEmployeeCode("C".repeat(64));
        assertNotNull(employeeRepo.saveAndFlush(e).getUserId());
    }

    // ...and 65 is rejected.
    @Test
    public void save_employeeCodeTooLong_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setEmployeeCode("C".repeat(65));
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    // Boundary: name is VARCHAR(128); 129 is rejected.
    @Test
    public void save_nameTooLong_throws() {
        EmpPojo e = anEmployee(ORG_1);
        e.setName("N".repeat(129));
        assertThrows(DataIntegrityViolationException.class, () -> employeeRepo.saveAndFlush(e));
    }

    @Test
    public void searchDirectory_excludesExited() {
        Long org = 501L;
        EmpPojo active = directoryEmp(org, EmpStatus.ACTIVE, "Active Ann", "HR", null);
        directoryEmp(org, EmpStatus.EXITED, "Gone Gus", "HR", null);

        Page<EmpPojo> page = search(org, null, null, null, 10);

        assertEquals(List.of(active.getUserId()), ids(page));
    }

    @Test
    public void searchDirectory_otherOrgExcluded() {
        directoryEmp(502L, EmpStatus.ACTIVE, "Other Org", "HR", null);
        assertEquals(0, search(503L, null, null, null, 10).getTotalElements());
    }

    // searchDirectory: department matches ignoring case; a null filter means "any".
    @Test
    public void searchDirectory_departmentIsCaseInsensitive() {
        Long org = 504L;
        EmpPojo eng = directoryEmp(org, EmpStatus.ACTIVE, "Eve", "Engineering", null);
        directoryEmp(org, EmpStatus.ACTIVE, "Hal", "HR", null);

        assertEquals(List.of(eng.getUserId()), ids(search(org, null, "eNGINEERING", null, 10)));
        assertEquals(2, search(org, null, null, null, 10).getTotalElements());
    }

    // searchDirectory: name search is a case-insensitive "contains"; people with no name
    // (null) never match a search but still appear when there is no search term.
    @Test
    public void searchDirectory_searchByName_ignoresCaseAndNullNames() {
        Long org = 505L;
        EmpPojo john = directoryEmp(org, EmpStatus.ACTIVE, "Johnny Smith", "HR", null);
        directoryEmp(org, EmpStatus.ACTIVE, "Mary Jones", "HR", null);
        directoryEmp(org, EmpStatus.ACTIVE, null, "HR", null);

        assertEquals(List.of(john.getUserId()), ids(search(org, null, null, "OHN", 10)));
        assertEquals(3, search(org, null, null, null, 10).getTotalElements());
        assertEquals(0, search(org, null, null, "zzz", 10).getTotalElements());
    }

    @Test
    public void searchDirectory_managerFilter_onlyDirectReports() {
        Long org = 506L;
        EmpPojo mgr = directoryEmp(org, EmpStatus.ACTIVE, "Boss", "HR", null);
        EmpPojo report = directoryEmp(org, EmpStatus.ACTIVE, "Report", "HR", mgr.getUserId());
        directoryEmp(org, EmpStatus.ACTIVE, "Other", "HR", null);

        assertEquals(List.of(report.getUserId()), ids(search(org, mgr.getUserId(), null, null, 10)));
    }

    @Test
    public void searchDirectory_paginates() {
        Long org = 507L;
        for (int i = 0; i < 3; i++) {
            directoryEmp(org, EmpStatus.ACTIVE, "P" + i, "HR", null);
        }

        Page<EmpPojo> first = search(org, null, null, null, 2);

        assertEquals(2, first.getContent().size());
        assertEquals(3, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
    }

    /* Private Helper Methods */

    private static EmpPojo anEmployee(Long orgId) {
        return EmpTestData.anEmployee(orgId, UID.incrementAndGet());
    }

    // Saves a real user (for its status) and an employee profile hanging off it.
    private EmpPojo directoryEmp(Long orgId, EmpStatus status, String name, String dept, Long managerId) {
        UserPojo u = UserTestData.aUser(orgId, "u" + UID.incrementAndGet() + "@dir.test");
        u = userRepo.save(u);
        EmpPojo e = EmpTestData.anEmployee(orgId, u.getId());
        e.setName(name);
        e.setDepartment(dept);
        e.setManagerId(managerId);
        e.setStatus(status);
        return employeeRepo.save(e);
    }

    private Page<EmpPojo> search(Long orgId, Long managerId, String dept, String q, int size) {
        return employeeRepo.searchDirectory(orgId, managerId, dept, q,
            PageRequest.of(0, size, Sort.by("userId")));
    }

    private static List<Long> ids(Page<EmpPojo> page) {
        return page.getContent().stream().map(EmpPojo::getUserId).toList();
    }
}
