package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.EmpImportResultData;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.EmpImportRowForm;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.EmpRepo;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

public class EmpImportFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmpImportFlow importFlow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private EmpRepo empRepo;

    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    @Test
    public void allValidRows_createsEachAsInvitedWithNoPassword() {
        Long orgId = anOrg("acme-imp-1");
        List<EmpImportRowForm> rows = List.of(
            row("alice@acme.test", UserRole.EMPLOYEE),
            row("bob@acme.test", UserRole.EMPLOYEE));

        EmpImportResultData result = importFlow.importEmployees(orgId, rows);

        assertEquals(2, result.getCreated());
        assertTrue(result.getSkipped().isEmpty());
        for (String email : List.of("alice@acme.test", "bob@acme.test")) {
            UserPojo user = userRepo.findByOrgIdAndEmail(orgId, email).orElseThrow();
            assertEquals(UserStatus.INVITED, user.getStatus());
            assertNull(user.getPasswordHash()); // the user sets one from the invite mail
        }
    }

    @Test
    public void mixedFile_perRowSkipLines_validRowsStillCreated() {
        Long orgId = anOrg("acme-imp-2");
        List<EmpImportRowForm> rows = List.of(
            row("", UserRole.EMPLOYEE),
            row("good@acme.test", UserRole.EMPLOYEE));

        EmpImportResultData result = importFlow.importEmployees(orgId, rows);

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).startsWith("row 1"));
        assertTrue(userRepo.existsByOrgIdAndEmail(orgId, "good@acme.test"));
    }

    @Test
    public void unknownManagerEmail_rowSkipped() {
        Long orgId = anOrg("acme-imp-3");
        EmpImportRowForm r = row("worker@acme.test", UserRole.EMPLOYEE);
        r.setManagerEmail("nobody@acme.test");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).contains("managerEmail"));
    }

    @Test
    public void managerEmailOfExistingUser_resolves() {
        Long orgId = anOrg("acme-imp-4");
        Long mgrId = hrEmpFlow.create(orgId, singleForm("carol@acme.test", UserRole.EMPLOYEE));
        EmpImportRowForm r = row("dave@acme.test", UserRole.EMPLOYEE);
        r.setManagerEmail("carol@acme.test");

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals(1, result.getCreated());
        Long daveId = userRepo.findByOrgIdAndEmail(orgId, "dave@acme.test").orElseThrow().getId();
        assertEquals(mgrId, empRepo.findById(daveId).orElseThrow().getManagerId());
    }

    @Test
    public void duplicateEmailWithinFile_secondSkipped() {
        Long orgId = anOrg("acme-imp-5");
        List<EmpImportRowForm> rows = List.of(
            row("dup@acme.test", UserRole.EMPLOYEE),
            row("dup@acme.test", UserRole.EMPLOYEE));

        EmpImportResultData result = importFlow.importEmployees(orgId, rows);

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).contains("duplicate email"));
    }

    @Test
    public void onlyNameEmailRole_isEnough_everythingElseDefaults() {
        Long orgId = anOrg("acme-imp-6");
        EmpImportRowForm r = new EmpImportRowForm();
        r.setName("  Min Row  ");
        r.setEmail("min@acme.test");
        r.setRole(UserRole.HR);

        EmpImportResultData result = importFlow.importEmployees(orgId, List.of(r));

        assertEquals(1, result.getCreated());
        UserPojo user = userRepo.findByOrgIdAndEmail(orgId, "min@acme.test").orElseThrow();
        assertEquals(UserStatus.INVITED, user.getStatus());
        assertEquals(UserRole.HR, user.getRole());
    }

    @Test
    public void missingName_rowSkipped_otherRowsStillCreated() {
        Long orgId = anOrg("acme-imp-7");
        EmpImportRowForm noName = row("noname@acme.test", UserRole.EMPLOYEE);
        noName.setName(" ");

        EmpImportResultData result = importFlow.importEmployees(orgId,
            List.of(noName, row("ok@acme.test", UserRole.EMPLOYEE)));

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getSkipped().size());
        assertTrue(result.getSkipped().get(0).contains("name is required"));
        assertTrue(!userRepo.existsByOrgIdAndEmail(orgId, "noname@acme.test"));
    }

    @Test
    public void emptyRows_badRequest() {
        Long orgId = anOrg("acme-imp-7");
        assertThrows(BadRequestException.class, () -> importFlow.importEmployees(orgId, List.of()));
    }

    @Test
    public void overMaxRows_badRequest() {
        Long orgId = anOrg("acme-imp-8");
        List<EmpImportRowForm> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("row" + i + "@acme.test", UserRole.EMPLOYEE));
        }
        assertThrows(BadRequestException.class, () -> importFlow.importEmployees(orgId, rows));
    }

    @Test
    public void reImport_sameFile_allSkippedAsDuplicates_createdZero() {
        Long orgId = anOrg("acme-imp-9");
        List<EmpImportRowForm> rows = List.of(row("repeat@acme.test", UserRole.EMPLOYEE));
        importFlow.importEmployees(orgId, rows);

        EmpImportResultData second = importFlow.importEmployees(orgId, rows);

        assertEquals(0, second.getCreated());
        assertEquals(1, second.getSkipped().size());
        assertTrue(second.getSkipped().get(0).contains("already exists"));
    }

    /* Private Helper Methods */

    // orgSvc.create bypasses AuthFlow.signup (which is what normally also creates the
    // org_subscription row) -- importEmployees now calls SubscriptionService.remainingSeats,
    // so every test org needs one too.
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

    private static EmpForm singleForm(String email, UserRole role) {
        EmpForm f = new EmpForm();
        f.setEmail(email);
        f.setRole(role);
        return f;
    }

    private static byte[] bytes(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
