package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.EmpImportResultData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.EmpImportRowForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.EmpRepo;
import com.leaveapp.repo.EmpSensitiveRepo;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;

// Gap-filler for EmpImportFlowTest: the per-row rejections (role, name, email, field
// shapes), manager-email handling, the seat limit, and a fully populated row.
public class EmpImportFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmpImportFlow importFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private EmpRepo empRepo;

    @Autowired
    private EmpSensitiveRepo sensRepo;

    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    @Test
    public void nullRows_badRequest() {
        Long orgId = anOrg("acme-impc-1");
        assertThrows(BadRequestException.class, () -> importFlow.importEmployees(orgId, null));
    }

    @Test
    public void exactlyMaxRows_isAccepted_andOverTheSeatLimitIsSkippedNotRejected() {
        Long orgId = anOrg("acme-impc-2"); // FREE plan: 5 seats
        List<EmpImportRowForm> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            rows.add(row("m" + i + "@acme.test", UserRole.EMPLOYEE));
        }

        EmpImportResultData result = importFlow.importEmployees(orgId, rows);

        assertEquals(5, result.getCreated());
        assertEquals(495, result.getSkipped().size());
    }

    @Test
    public void nullRole_rowSkipped() {
        Long orgId = anOrg("acme-impc-3");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(row("norole@acme.test", null)));

        assertEquals(0, result.getCreated());
        assertEquals("row 1 (norole@acme.test): role must be EMPLOYEE or HR", result.getSkipped().get(0));
    }

    @Test
    public void orgAdminRole_rowSkipped() {
        Long orgId = anOrg("acme-impc-4");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(row("boss@acme.test", UserRole.ORG_ADMIN)));

        assertEquals(0, result.getCreated());
        assertTrue(result.getSkipped().get(0).contains("role must be EMPLOYEE or HR"));
    }

    @Test
    public void hrRole_isAccepted() {
        Long orgId = anOrg("acme-impc-5");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(row("hr@acme.test", UserRole.HR)));

        assertEquals(1, result.getCreated());
    }

    @Test
    public void nullName_rowSkipped() {
        Long orgId = anOrg("acme-impc-6");
        EmpImportRowForm r = row("noname@acme.test", UserRole.EMPLOYEE);
        r.setName(null);

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals(0, result.getCreated());
        assertTrue(result.getSkipped().get(0).contains("name is required"));
    }

    @Test
    public void nullEmail_rowSkippedWithEmptyEmailInTheSkipLine() {
        Long orgId = anOrg("acme-impc-7");
        EmpImportRowForm r = row(null, UserRole.EMPLOYEE);

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals("row 1 (): email is required", result.getSkipped().get(0));
    }

    @Test
    public void malformedEmail_rowSkipped() {
        Long orgId = anOrg("acme-impc-8");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(row("not-an-email", UserRole.EMPLOYEE)));

        assertEquals(0, result.getCreated());
        assertTrue(result.getSkipped().get(0).contains("valid email"));
    }

    @Test
    public void emailWithSurroundingSpaces_isTrimmed() {
        Long orgId = anOrg("acme-impc-9");

        importFlow.importEmployees(orgId, List.of(row("  spaced@acme.test  ", UserRole.EMPLOYEE)));

        assertTrue(userRepo.existsByOrgIdAndEmail(orgId, "spaced@acme.test"));
    }

    @Test
    public void badFieldShapes_eachRowSkippedWithItsOwnReason() {
        Long orgId = anOrg("acme-impc-10");
        EmpImportRowForm badPhone = row("p@acme.test", UserRole.EMPLOYEE);
        badPhone.setPhone("12345");
        EmpImportRowForm longCode = row("c@acme.test", UserRole.EMPLOYEE);
        longCode.setEmployeeCode("x".repeat(65));
        EmpImportRowForm longType = row("t@acme.test", UserRole.EMPLOYEE);
        longType.setEmployeeType("x".repeat(33));
        EmpImportRowForm longTitle = row("d@acme.test", UserRole.EMPLOYEE);
        longTitle.setDesignation("x".repeat(129));
        EmpImportRowForm longDept = row("e@acme.test", UserRole.EMPLOYEE);
        longDept.setDepartment("x".repeat(129));
        EmpImportRowForm longName = row("n@acme.test", UserRole.EMPLOYEE);
        longName.setName("x".repeat(129));

        EmpImportResultData result = importFlow.importEmployees(orgId,
            List.of(badPhone, longCode, longType, longTitle, longDept, longName));

        assertEquals(0, result.getCreated());
        assertEquals(6, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).startsWith("row 1 (p@acme.test): phone"));
        assertTrue(result.getSkipped().get(1).contains("employeeCode"));
        assertTrue(result.getSkipped().get(2).contains("employeeType"));
        assertTrue(result.getSkipped().get(3).contains("designation"));
        assertTrue(result.getSkipped().get(4).contains("department"));
        assertTrue(result.getSkipped().get(5).contains("name"));
    }

    @Test
    public void blankManagerEmail_meansNoManager() {
        Long orgId = anOrg("acme-impc-11");
        EmpImportRowForm r = row("nomgr@acme.test", UserRole.EMPLOYEE);
        r.setManagerEmail("   ");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals(1, result.getCreated());
        assertNull(empRepo.findById(idOf(orgId, "nomgr@acme.test")).orElseThrow().getManagerId());
    }

    @Test
    public void managerEmailWithSurroundingSpaces_resolvesAgainstTheOrg() {
        Long orgId = anOrg("acme-impc-12");
        importFlow.importEmployees(orgId, List.of(row("boss12@acme.test", UserRole.HR)));
        EmpImportRowForm r = row("rep12@acme.test", UserRole.EMPLOYEE);
        r.setManagerEmail("  boss12@acme.test ");

        importFlow.importEmployees(orgId, List.of(r));

        assertEquals(idOf(orgId, "boss12@acme.test"), empRepo.findById(idOf(orgId, "rep12@acme.test")).orElseThrow().getManagerId());
    }

    @Test
    public void managerInAnotherOrg_rowSkipped() {
        Long orgA = anOrg("acme-impc-13a");
        Long orgB = anOrg("acme-impc-13b");
        importFlow.importEmployees(orgB, List.of(row("boss13@b.test", UserRole.HR)));
        EmpImportRowForm r = row("rep13@a.test", UserRole.EMPLOYEE);
        r.setManagerEmail("boss13@b.test");

        EmpImportResultData result = importFlow.importEmployees(orgA, List.of(r));

        assertEquals(0, result.getCreated());
        assertTrue(result.getSkipped().get(0).contains("managerEmail"));
    }

    @Test
    public void fullyPopulatedRow_isPersistedFieldForField() {
        Long orgId = anOrg("acme-impc-14");
        importFlow.importEmployees(orgId, List.of(row("boss14@acme.test", UserRole.HR)));
        EmpImportRowForm r = row("full14@acme.test", UserRole.EMPLOYEE);
        r.setName("  Fully Populated ");
        r.setEmployeeCode("CODE-14");
        r.setPhone("+91 98765 43210");
        r.setDateOfJoining(LocalDate.of(2022, 2, 2));
        r.setDateOfExit(LocalDate.of(2032, 2, 2));
        r.setEmployeeType("CONTRACT");
        r.setDesignation("Engineer");
        r.setDepartment("R&D");
        r.setManagerEmail("boss14@acme.test");
        r.setBasicSalary(new BigDecimal("999.99"));

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals(1, result.getCreated());
        Long id = idOf(orgId, "full14@acme.test");
        EmpPojo emp = empRepo.findById(id).orElseThrow();
        assertEquals("Fully Populated", emp.getName());
        assertEquals("CODE-14", emp.getEmployeeCode());
        assertEquals(LocalDate.of(2022, 2, 2), emp.getDateOfJoining());
        assertEquals(LocalDate.of(2032, 2, 2), emp.getDateOfExit());
        assertEquals("CONTRACT", emp.getEmployeeType());
        assertEquals("Engineer", emp.getDesignation());
        assertEquals("R&D", emp.getDepartment());
        assertEquals(idOf(orgId, "boss14@acme.test"), emp.getManagerId());
        assertEquals(0, new BigDecimal("999.99").compareTo(emp.getBasicSalary()));
        assertEquals("+91 98765 43210", sensRepo.findById(id).orElseThrow().getPhone());
    }

    @Test
    public void moreRowsThanSeats_fillsTheSeatsAndSkipsTheRestWithAReason() {
        Long orgId = anOrg("acme-impc-15"); // 5 seats
        List<EmpImportRowForm> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(row("seat" + i + "@acme.test", UserRole.EMPLOYEE));
        }

        EmpImportResultData result = importFlow.importEmployees(orgId, rows);

        assertEquals(5, result.getCreated());
        assertEquals(2, result.getSkipped().size());
        assertEquals("row 6 (seat5@acme.test): seat limit reached for your current plan", result.getSkipped().get(0));
        assertTrue(result.getSkipped().get(1).startsWith("row 7"));
    }

    @Test
    public void noSeatsLeft_everyRowSkipped() {
        Long orgId = anOrg("acme-impc-16");
        List<EmpImportRowForm> fill = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fill.add(row("fill" + i + "@acme.test", UserRole.EMPLOYEE));
        }
        importFlow.importEmployees(orgId, fill);

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(row("late@acme.test", UserRole.EMPLOYEE)));

        assertEquals(0, result.getCreated());
        assertTrue(result.getSkipped().get(0).contains("seat limit reached"));
    }

    @Test
    public void invalidRowsDoNotConsumeSeats() {
        Long orgId = anOrg("acme-impc-17"); // 5 seats
        List<EmpImportRowForm> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("", UserRole.EMPLOYEE)); // five invalid rows first
        }
        for (int i = 0; i < 5; i++) {
            rows.add(row("good" + i + "@acme.test", UserRole.EMPLOYEE));
        }

        EmpImportResultData result = importFlow.importEmployees(orgId, rows);

        assertEquals(5, result.getCreated());
        assertEquals(5, result.getSkipped().size());
    }

    /* Private Helper Methods */

    private Long idOf(Long orgId, String email) {
        UserPojo user = userRepo.findByOrgIdAndEmail(orgId, email).orElseThrow();
        return user.getId();
    }

    private Long anOrg(String username) {
        OrgPojo org = orgSvc.create("Acme", username, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        return org.getId();
    }

    private static EmpImportRowForm row(String email, UserRole role) {
        EmpImportRowForm r = new EmpImportRowForm();
        r.setName("Test " + email);
        r.setEmail(email);
        r.setRole(role);
        return r;
    }
}
