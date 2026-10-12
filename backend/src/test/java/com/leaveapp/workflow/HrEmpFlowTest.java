package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.repo.EmpRepo;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.CredentialService;
import com.leaveapp.service.UserService;

// Cross-org and per-target-role authorization now live in HrEmpController (see
// HrEmpControllerTest) -- this Flow trusts orgId/targetId as already authorized.
public class HrEmpFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private HrEmpFlow flow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private CredentialService credSvc;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private EmpRepo empRepo;

    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    @Test
    public void create_writesInvitedUserWithNoPassword_andEmployeeDefaults() {
        Long orgId = anOrg("acme-hr-1");

        EmpForm form = form("worker@acme.test", UserRole.EMPLOYEE);
        form.setName("Wilma Worker");

        Long userId = flow.create(orgId, form);

        UserPojo user = userRepo.findById(userId).orElseThrow();
        assertEquals("EMPLOYEE", user.getRole().name());
        assertEquals("INVITED", user.getStatus().name());
        assertNull(user.getPasswordHash()); // set later from the invite mail
        assertThrows(NotAuthException.class, // INVITED: the invite link is the only way in
            () -> credSvc.verifyLogin(orgId, "worker@acme.test", bytes("s3cret!!")));

        EmpPojo emp = empRepo.findById(userId).orElseThrow();
        assertEquals(orgId, emp.getOrgId());
        assertEquals("Wilma Worker", emp.getName());
        assertNull(emp.getEmployeeCode()); // no placeholder -- HR sets it later
        assertEquals("REGULAR", emp.getEmployeeType());
        assertNull(emp.getDateOfJoining());
        assertEquals(EmpStatus.ONBOARDING, emp.getStatus());
    }

    @Test
    public void create_hrRole_isWritten() {
        Long orgId = anOrg("acme-hr-2");
        Long userId = flow.create(orgId, form("hr2@acme.test", UserRole.HR));
        assertEquals("HR", userRepo.findById(userId).orElseThrow().getRole().name());
    }

    @Test
    public void invite_createsInvitedUserWithNameAndRoleOnly() {
        Long orgId = anOrg("acme-hr-inv1");
        UserInviteForm form = new UserInviteForm();
        form.setName("  Ivy Invitee ");
        form.setEmail("ivy@acme.test");
        form.setRole(UserRole.EMPLOYEE);

        Long userId = flow.invite(orgId, form);

        UserPojo user = userRepo.findById(userId).orElseThrow();
        assertEquals("INVITED", user.getStatus().name());
        assertNull(user.getPasswordHash());
        assertEquals("Ivy Invitee", empRepo.findById(userId).orElseThrow().getName());
    }

    @Test
    public void invite_blankName_badRequest() {
        Long orgId = anOrg("acme-hr-inv2");
        UserInviteForm form = new UserInviteForm();
        form.setName(" ");
        form.setEmail("noname@acme.test");
        form.setRole(UserRole.EMPLOYEE);
        assertThrows(BadRequestException.class, () -> flow.invite(orgId, form));
    }

    @Test
    public void resendInvite_onlyForInvitedUsers() {
        Long orgId = anOrg("acme-hr-inv3");
        UserInviteForm form = new UserInviteForm();
        form.setName("Rey Resend");
        form.setEmail("rey@acme.test");
        form.setRole(UserRole.EMPLOYEE);
        Long userId = flow.invite(orgId, form);

        flow.resendInvite(userId); // INVITED: fine

        userRepo.findById(userId).orElseThrow().setStatus(UserStatus.PENDING);
        assertThrows(BadRequestException.class, () -> flow.resendInvite(userId));
    }

    @Test
    public void create_duplicateEmailInSameOrg_conflict() {
        Long orgId = anOrg("acme-hr-3");
        flow.create(orgId, form("dupe@acme.test", UserRole.EMPLOYEE));
        assertThrows(ConflictException.class,
            () -> flow.create(orgId, form("dupe@acme.test", UserRole.EMPLOYEE)));
    }

    @Test
    public void create_unknownManagerId_badRequest() {
        Long orgId = anOrg("acme-hr-4");
        EmpForm form = form("m1@acme.test", UserRole.EMPLOYEE);
        form.setManagerId(999999L);
        assertThrows(BadRequestException.class, () -> flow.create(orgId, form));
    }

    @Test
    public void create_crossOrgManagerId_badRequest() {
        Long orgA = anOrg("acme-hr-5a");
        Long orgB = anOrg("acme-hr-5b");
        UserPojo mgrInB = userSvc.createUser(orgB, "mgr@b.test", bytes("s3cret!!"), UserRole.HR);

        EmpForm form = form("worker@a.test", UserRole.EMPLOYEE);
        form.setManagerId(mgrInB.getId());
        assertThrows(BadRequestException.class, () -> flow.create(orgA, form));
    }

    @Test
    public void create_validManagerIdInSameOrg_isAccepted() {
        Long orgId = anOrg("acme-hr-6");
        UserPojo mgr = userSvc.createUser(orgId, "mgr@acme.test", bytes("s3cret!!"), UserRole.HR);

        EmpForm form = form("report@acme.test", UserRole.EMPLOYEE);
        form.setManagerId(mgr.getId());
        Long userId = flow.create(orgId, form);

        assertEquals(mgr.getId(), empRepo.findById(userId).orElseThrow().getManagerId());
    }

    // An unknown role string ("MANAGER") is no longer reachable here -- EmpForm.role
    // is now typed UserRole, so Jackson rejects it at the HTTP boundary (400) before it
    // could ever reach the Flow. Only the null case is still exercised at this layer.
    @Test
    public void create_blankRole_badRequest() {
        Long orgId = anOrg("acme-hr-7");
        assertThrows(BadRequestException.class,
            () -> flow.create(orgId, form("x@acme.test", null)));
    }

    // No password from HR at all in this design (onboarding redesign) -- the employee
    // sets their own via the invite email. passwordHash stays null until then.
    @Test
    public void create_blankPassword_succeedsWithNullPasswordHash() {
        Long orgId = anOrg("acme-hr-8");
        Long userId = flow.create(orgId, form("z@acme.test", UserRole.EMPLOYEE));
        assertNull(userRepo.findById(userId).orElseThrow().getPasswordHash());
    }

    @Test
    public void create_blankEmail_badRequest() {
        Long orgId = anOrg("acme-hr-9");
        assertThrows(BadRequestException.class,
            () -> flow.create(orgId, form("   ", UserRole.EMPLOYEE)));
    }

    @Test
    public void list_returnsOnlyThisOrgsEmployees_withZippedShape() {
        Long orgA = anOrg("acme-hr-10a");
        Long orgB = anOrg("acme-hr-10b");

        EmpForm f = form("wanda@a.test", UserRole.HR);
        f.setName("Wanda");
        f.setDepartment("People");
        Long wandaId = flow.create(orgA, f);
        flow.create(orgB, form("other@b.test", UserRole.EMPLOYEE));

        List<EmpRowData> rows = flow.list(orgA);

        assertEquals(1, rows.size());
        EmpRowData row = rows.get(0);
        assertEquals(wandaId, row.getId());
        assertEquals("wanda@a.test", row.getEmail());
        assertEquals("HR", row.getRole());   // from the user row
        assertEquals("INVITED", row.getStatus());   // from the user row
        assertEquals("Wanda", row.getName());      // from the employee row
        assertEquals("People", row.getDepartment());
        assertNull(row.getEmployeeCode());
    }

    @Test
    public void list_emptyOrg_returnsEmptyList() {
        Long orgId = anOrg("acme-hr-11");
        assertTrue(flow.list(orgId).isEmpty());
    }

    @Test
    public void update_changesUserAndProfileFields_returnsUpdatedRow() {
        Long orgId = anOrg("acme-hr-12");
        Long id = flow.create(orgId, form("u12@acme.test", UserRole.EMPLOYEE));

        userRepo.findById(id).orElseThrow().setStatus(UserStatus.ACTIVE); // status edits need a finished onboarding
        empRepo.findById(id).orElseThrow().setStatus(EmpStatus.ACTIVE);

        empRepo.findById(id).orElseThrow().setStatus(EmpStatus.ACTIVE);
        EmpForm patch = new EmpForm();
        patch.setEmail("u12-new@acme.test");
        patch.setRole(UserRole.HR);
        patch.setStatus("ON_NOTICE");
        patch.setName("Renamed");
        patch.setDepartment("Ops");
        patch.setBasicSalary(new BigDecimal("12345.67"));

        EmpRowData row = flow.update(orgId, id, patch);

        assertEquals("u12-new@acme.test", row.getEmail());
        assertEquals("HR", row.getRole());
        assertEquals("ON_NOTICE", row.getStatus());
        assertEquals("Renamed", row.getName());
        assertEquals("Ops", row.getDepartment());
        assertEquals(0, new BigDecimal("12345.67").compareTo(row.getBasicSalary()));

        UserPojo user = userRepo.findById(id).orElseThrow();
        assertEquals("u12-new@acme.test", user.getEmail());
        assertEquals("HR", user.getRole().name());
        assertEquals("ACTIVE", user.getStatus().name()); // account stays usable on notice
        assertEquals(EmpStatus.ON_NOTICE, empRepo.findById(id).orElseThrow().getStatus());
    }

    @Test
    public void update_roleChange_revokesExistingSessions() {
        Long orgId = anOrg("acme-hr-27");
        Long id = flow.create(orgId, form("u27@acme.test", UserRole.EMPLOYEE));
        assertNull(userRepo.findById(id).orElseThrow().getTokensValidAfter());

        EmpForm patch = new EmpForm();
        patch.setRole(UserRole.HR);
        flow.update(orgId, id, patch);

        assertNotNull(userRepo.findById(id).orElseThrow().getTokensValidAfter());
    }

    @Test
    public void update_nullFields_leaveExistingValues() {
        Long orgId = anOrg("acme-hr-13");
        EmpForm create = form("u13@acme.test", UserRole.EMPLOYEE);
        create.setName("Keep Me");
        create.setDepartment("Keep Dept");
        Long id = flow.create(orgId, create);

        EmpRowData row = flow.update(orgId, id, new EmpForm());

        assertEquals("u13@acme.test", row.getEmail());
        assertEquals("EMPLOYEE", row.getRole());
        assertEquals("Keep Me", row.getName());
        assertEquals("Keep Dept", row.getDepartment());
    }

    @Test
    public void update_statusChange_blockedWhileOnboarding_exitStillAllowed() {
        Long orgId = anOrg("acme-hr-tamper");
        Long id = flow.create(orgId, form("tamper@acme.test", UserRole.EMPLOYEE)); // INVITED

        EmpForm toActive = new EmpForm();
        toActive.setStatus("ACTIVE");
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, toActive));
        assertEquals(UserStatus.INVITED, userRepo.findById(id).orElseThrow().getStatus());

        EmpForm toExited = new EmpForm();
        toExited.setStatus("EXITED");
        flow.update(orgId, id, toExited);
        assertEquals(UserStatus.DISABLED, userRepo.findById(id).orElseThrow().getStatus());
        assertEquals(EmpStatus.EXITED, empRepo.findById(id).orElseThrow().getStatus());
    }
    @Test
    public void update_emailToTakenAddress_conflict() {
        Long orgId = anOrg("acme-hr-15");
        flow.create(orgId, form("taken@acme.test", UserRole.EMPLOYEE));
        Long id = flow.create(orgId, form("mover@acme.test", UserRole.EMPLOYEE));

        EmpForm patch = new EmpForm();
        patch.setEmail("taken@acme.test");
        assertThrows(ConflictException.class, () -> flow.update(orgId, id, patch));
    }

    @Test
    public void update_emailToSameValue_isNoop() {
        Long orgId = anOrg("acme-hr-16");
        Long id = flow.create(orgId, form("same@acme.test", UserRole.EMPLOYEE));

        EmpForm patch = new EmpForm();
        patch.setEmail("same@acme.test");
        assertEquals("same@acme.test", flow.update(orgId, id, patch).getEmail());
    }

    @Test
    public void update_unknownTarget_notFound() {
        Long orgId = anOrg("acme-hr-17");
        assertThrows(NotFoundException.class, () -> flow.update(orgId, 999999L, new EmpForm()));
    }

    @Test
    public void update_selfManager_badRequest() {
        Long orgId = anOrg("acme-hr-20");
        Long id = flow.create(orgId, form("u20@acme.test", UserRole.EMPLOYEE));

        EmpForm patch = new EmpForm();
        patch.setManagerId(id);
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, patch));
    }

    @Test
    public void update_unknownManagerId_badRequest() {
        Long orgId = anOrg("acme-hr-21");
        Long id = flow.create(orgId, form("u21@acme.test", UserRole.EMPLOYEE));

        EmpForm patch = new EmpForm();
        patch.setManagerId(888888L);
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, patch));
    }

    // An invalid role string is no longer reachable here -- see create_blankRole_badRequest.
    @Test
    public void update_invalidStatus_badRequest() {
        Long orgId = anOrg("acme-hr-22");
        Long id = flow.create(orgId, form("u22@acme.test", UserRole.EMPLOYEE));

        EmpForm badStatus = new EmpForm();
        badStatus.setStatus("PARTY");
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, badStatus));
    }

    @Test
    public void deactivate_setsExitedAndExitDate_blocksLogin() {
        Long orgId = anOrg("acme-hr-23");
        Long id = flow.create(orgId, form("u23@acme.test", UserRole.EMPLOYEE));

        flow.deactivate(id);

        UserPojo user = userRepo.findById(id).orElseThrow();
        assertEquals("DISABLED", user.getStatus().name());
        assertEquals(EmpStatus.EXITED, empRepo.findById(id).orElseThrow().getStatus());
        assertEquals(LocalDate.now(), empRepo.findById(id).orElseThrow().getDateOfExit());
        assertThrows(NotAuthException.class,
            () -> credSvc.verifyLogin(orgId, "u23@acme.test", bytes("s3cret!!")));
    }

    @Test
    public void deactivate_alreadyExited_isNoopKeepingOriginalExitDate() {
        Long orgId = anOrg("acme-hr-24");
        Long id = flow.create(orgId, form("u24@acme.test", UserRole.EMPLOYEE));

        EmpForm patch = new EmpForm();
        patch.setStatus("EXITED");
        patch.setDateOfExit(LocalDate.of(2020, 1, 1));
        flow.update(orgId, id, patch);

        flow.deactivate(id);

        assertEquals(LocalDate.of(2020, 1, 1), empRepo.findById(id).orElseThrow().getDateOfExit());
    }

    @Test
    public void deactivate_unknownTarget_notFound() {
        assertThrows(NotFoundException.class, () -> flow.deactivate(999999L));
    }

    /* Private Helper Methods */

    // orgSvc.create bypasses AuthFlow.signup (which is what normally also creates the
    // org_subscription row) -- flow.create now asserts a subscription exists
    // (SubscriptionService.assertSeatAvailable), so every test org needs one too.
    private Long anOrg(String username) {
        OrgPojo org = orgSvc.create("Acme", username, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        return org.getId();
    }

    private static EmpForm form(String email, UserRole role) {
        EmpForm f = new EmpForm();
        f.setEmail(email);
        f.setRole(role);
        return f;
    }

    private static byte[] bytes(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
