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
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.EmpRepo;
import com.leaveapp.repo.EmpSensitiveRepo;
import com.leaveapp.repo.EmpTestData;
import com.leaveapp.repo.UserRepo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// Gap-filler for HrEmpFlowTest: the optional-field arms of create, the status / role /
// manager rules of update, the seat limit and the rows list() has to skip.
public class HrEmpFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private HrEmpFlow flow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

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
    public void list_employeeWhoseUserIsInAnotherOrg_isSkipped() {
        Long orgA = anOrg("acme-hrc-1a");
        Long orgB = anOrg("acme-hrc-1b");
        UserPojo foreign = userSvc.createUser(orgB, "foreign@b.test", pwd("s3cret!!"), UserRole.EMPLOYEE);
        empSvc.createProfile(EmpTestData.anEmployee(orgA, foreign.getId())); // profile says org A, user says org B
        Long ok = flow.create(orgA, form("ok@a.test", UserRole.EMPLOYEE));

        List<EmpRowData> rows = flow.list(orgA);

        assertEquals(1, rows.size());
        assertEquals(ok, rows.get(0).getId());
    }

    @Test
    public void list_includesPhoneFromTheSensitiveRow() {
        Long orgId = anOrg("acme-hrc-2");
        EmpForm f = form("phone@acme.test", UserRole.EMPLOYEE);
        f.setPhone("+91 98765 43210");
        flow.create(orgId, f);

        assertEquals("+91 98765 43210", flow.list(orgId).get(0).getPhone());
    }

    @Test
    public void create_nullEmail_badRequest() {
        Long orgId = anOrg("acme-hrc-3");
        assertThrows(BadRequestException.class, () -> flow.create(orgId, form(null, UserRole.EMPLOYEE)));
    }

    @Test
    public void create_malformedEmail_badRequest() {
        Long orgId = anOrg("acme-hrc-4");
        assertThrows(BadRequestException.class, () -> flow.create(orgId, form("not-an-email", UserRole.EMPLOYEE)));
    }

    @Test
    public void create_orgAdminRole_badRequest() {
        Long orgId = anOrg("acme-hrc-5");
        assertThrows(BadRequestException.class, () -> flow.create(orgId, form("boss@acme.test", UserRole.ORG_ADMIN)));
    }

    @Test
    public void create_emailIsTrimmed() {
        Long orgId = anOrg("acme-hrc-6");

        Long id = flow.create(orgId, form("  Padded@Acme.test ", UserRole.EMPLOYEE));

        assertEquals("Padded@Acme.test", userRepo.findById(id).orElseThrow().getEmail());
    }

    @Test
    public void create_everyOptionalFieldSupplied_isPersisted() {
        Long orgId = anOrg("acme-hrc-7");
        Long mgr = flow.create(orgId, form("mgr7@acme.test", UserRole.HR));
        EmpForm f = form("full7@acme.test", UserRole.EMPLOYEE);
        f.setEmployeeCode("  CODE-7 ");
        f.setName("Full Seven");
        f.setPhone("+91 98765 43210");
        f.setDateOfJoining(LocalDate.of(2021, 6, 1));
        f.setDateOfExit(LocalDate.of(2031, 6, 1));
        f.setEmployeeType(" CONTRACT ");
        f.setDesignation("Engineer");
        f.setDepartment("R&D");
        f.setManagerId(mgr);
        f.setBasicSalary(new BigDecimal("1234.50"));

        Long id = flow.create(orgId, f);

        EmpPojo emp = empRepo.findById(id).orElseThrow();
        assertEquals("CODE-7", emp.getEmployeeCode());
        assertEquals("Full Seven", emp.getName());
        assertEquals(LocalDate.of(2021, 6, 1), emp.getDateOfJoining());
        assertEquals(LocalDate.of(2031, 6, 1), emp.getDateOfExit());
        assertEquals("CONTRACT", emp.getEmployeeType());
        assertEquals("Engineer", emp.getDesignation());
        assertEquals("R&D", emp.getDepartment());
        assertEquals(mgr, emp.getManagerId());
        assertEquals(0, new BigDecimal("1234.50").compareTo(emp.getBasicSalary()));
        assertEquals("+91 98765 43210", sensRepo.findById(id).orElseThrow().getPhone());
    }

    @Test
    public void create_blankCodeAndType_codeNullTypeDefaults() {
        Long orgId = anOrg("acme-hrc-8");
        EmpForm f = form("blank8@acme.test", UserRole.EMPLOYEE);
        f.setEmployeeCode("   ");
        f.setEmployeeType("   ");

        EmpPojo emp = empRepo.findById(flow.create(orgId, f)).orElseThrow();

        assertNull(emp.getEmployeeCode());
        assertEquals("REGULAR", emp.getEmployeeType());
    }

    @Test
    public void create_afterSeatLimitReached_conflict() {
        Long orgId = anOrg("acme-hrc-9"); // FREE plan: 5 seats
        for (int i = 0; i < 5; i++) {
            flow.create(orgId, form("seat" + i + "@acme.test", UserRole.EMPLOYEE));
        }

        assertThrows(ConflictException.class, () -> flow.create(orgId, form("seat6@acme.test", UserRole.EMPLOYEE)));
    }

    @Test
    public void create_exitedAccountFreesItsSeat() {
        Long orgId = anOrg("acme-hrc-10");
        Long first = flow.create(orgId, form("seat0@acme.test", UserRole.EMPLOYEE));
        for (int i = 1; i < 5; i++) {
            flow.create(orgId, form("seat" + i + "@acme.test", UserRole.EMPLOYEE));
        }
        flow.deactivate(first);

        assertNotNull(flow.create(orgId, form("seat6@acme.test", UserRole.EMPLOYEE)));
    }

    @Test
    public void invite_nullName_badRequest() {
        Long orgId = anOrg("acme-hrc-11");
        UserInviteForm f = invite(null, "noname@acme.test");

        assertThrows(BadRequestException.class, () -> flow.invite(orgId, f));
    }

    @Test
    public void invite_overLongName_badRequest() {
        Long orgId = anOrg("acme-hrc-12");
        UserInviteForm f = invite("x".repeat(129), "long@acme.test");

        assertThrows(BadRequestException.class, () -> flow.invite(orgId, f));
    }

    @Test
    public void invite_nameWithMarkup_badRequest() {
        Long orgId = anOrg("acme-hrc-13");
        UserInviteForm f = invite("<script>", "xss@acme.test");

        assertThrows(BadRequestException.class, () -> flow.invite(orgId, f));
    }

    @Test
    public void resendInvite_unknownUser_notFound() {
        assertThrows(NotFoundException.class, () -> flow.resendInvite(987654L));
    }

    @Test
    public void update_sameRole_doesNotRevokeSessions() {
        Long orgId = anOrg("acme-hrc-14");
        Long id = flow.create(orgId, form("same14@acme.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setRole(UserRole.EMPLOYEE);

        flow.update(orgId, id, patch);

        assertNull(userRepo.findById(id).orElseThrow().getTokensValidAfter());
    }

    @Test
    public void update_orgAdminRole_badRequest() {
        Long orgId = anOrg("acme-hrc-15");
        Long id = flow.create(orgId, form("promo15@acme.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setRole(UserRole.ORG_ADMIN);

        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, patch));
    }

    @Test
    public void update_blankEmail_leavesEmailUnchanged() {
        Long orgId = anOrg("acme-hrc-16");
        Long id = flow.create(orgId, form("keep16@acme.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setEmail("   ");

        assertEquals("keep16@acme.test", flow.update(orgId, id, patch).getEmail());
    }

    @Test
    public void update_emailIsTrimmed() {
        Long orgId = anOrg("acme-hrc-17");
        Long id = flow.create(orgId, form("old17@acme.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setEmail("  new17@acme.test ");

        assertEquals("new17@acme.test", flow.update(orgId, id, patch).getEmail());
    }

    @Test
    public void update_statusOfPendingOrOnboardedUser_canOnlyMoveToExited() {
        Long orgId = anOrg("acme-hrc-18");
        Long pending = flow.create(orgId, form("pend18@acme.test", UserRole.EMPLOYEE));
        Long onboarded = flow.create(orgId, form("onb18@acme.test", UserRole.EMPLOYEE));
        userRepo.findById(pending).orElseThrow().setStatus(UserStatus.PENDING);
        userRepo.findById(onboarded).orElseThrow().setStatus(UserStatus.ACTIVE);
        empRepo.findById(onboarded).orElseThrow().setStatus(EmpStatus.ONBOARDED);
        EmpForm toActive = new EmpForm();
        toActive.setStatus("ACTIVE");
        EmpForm toNotice = new EmpForm();
        toNotice.setStatus("ON_NOTICE");
        EmpForm toExited = new EmpForm();
        toExited.setStatus("EXITED");

        assertThrows(BadRequestException.class, () -> flow.update(orgId, pending, toActive));
        assertThrows(BadRequestException.class, () -> flow.update(orgId, onboarded, toNotice));
        assertEquals("EXITED", flow.update(orgId, pending, toExited).getStatus());
        assertEquals("EXITED", flow.update(orgId, onboarded, toExited).getStatus());
    }

    @Test
    public void update_activeUserMovesFreelyBetweenActiveNoticeAndExited() {
        Long orgId = anOrg("acme-hrc-19");
        Long id = flow.create(orgId, form("act19@acme.test", UserRole.EMPLOYEE));
        userRepo.findById(id).orElseThrow().setStatus(UserStatus.ACTIVE);
        empRepo.findById(id).orElseThrow().setStatus(EmpStatus.ACTIVE);

        assertEquals("ON_NOTICE", flow.update(orgId, id, status("ON_NOTICE")).getStatus());
        assertEquals("ACTIVE", flow.update(orgId, id, status("ACTIVE")).getStatus());
        assertEquals("EXITED", flow.update(orgId, id, status("EXITED")).getStatus());
    }

    @Test
    public void update_statusThatIsAValidEnumButNotAllowed_badRequest() {
        Long orgId = anOrg("acme-hrc-20");
        Long id = flow.create(orgId, form("enum20@acme.test", UserRole.EMPLOYEE));
        userRepo.findById(id).orElseThrow().setStatus(UserStatus.ACTIVE);
        empRepo.findById(id).orElseThrow().setStatus(EmpStatus.ACTIVE);

        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, status("PENDING")));
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, status("INVITED")));
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, status("ONBOARDED")));
    }

    @Test
    public void update_statusNotAnEnumAtAll_badRequest() {
        Long orgId = anOrg("acme-hrc-21");
        Long id = flow.create(orgId, form("junk21@acme.test", UserRole.EMPLOYEE));

        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, status("nonsense")));
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, status("active"))); // case-sensitive
        assertThrows(BadRequestException.class, () -> flow.update(orgId, id, status("")));
    }

    @Test
    public void update_validManagerInSameOrg_isApplied() {
        Long orgId = anOrg("acme-hrc-22");
        Long mgr = flow.create(orgId, form("mgr22@acme.test", UserRole.HR));
        Long id = flow.create(orgId, form("rep22@acme.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setManagerId(mgr);

        EmpRowData row = flow.update(orgId, id, patch);

        assertEquals(mgr, row.getManagerId());
        assertEquals(mgr, empRepo.findById(id).orElseThrow().getManagerId());
    }

    @Test
    public void update_managerFromAnotherOrg_badRequest() {
        Long orgA = anOrg("acme-hrc-23a");
        Long orgB = anOrg("acme-hrc-23b");
        Long foreignMgr = flow.create(orgB, form("mgr23@b.test", UserRole.HR));
        Long id = flow.create(orgA, form("rep23@a.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setManagerId(foreignMgr);

        assertThrows(BadRequestException.class, () -> flow.update(orgA, id, patch));
    }

    @Test
    public void update_employeeWithoutSensitiveRow_createsItAndAppliesPhone() {
        Long orgId = anOrg("acme-hrc-24");
        Long id = flow.create(orgId, form("legacy24@acme.test", UserRole.EMPLOYEE));
        sensRepo.deleteById(id); // an account from before the sensitive module
        sensRepo.flush();
        EmpForm patch = new EmpForm();
        patch.setPhone("+91 98765 43210");

        EmpRowData row = flow.update(orgId, id, patch);

        assertEquals("+91 98765 43210", row.getPhone());
        assertNotNull(sensRepo.findById(id).orElse(null));
    }

    @Test
    public void update_profileFields_areAppliedThroughApplyAdminFields() {
        Long orgId = anOrg("acme-hrc-25");
        Long id = flow.create(orgId, form("prof25@acme.test", UserRole.EMPLOYEE));
        EmpForm patch = new EmpForm();
        patch.setEmployeeCode("NEW-25");
        patch.setDesignation("Lead");
        patch.setEmployeeType("INTERN");
        patch.setDateOfJoining(LocalDate.of(2019, 1, 1));
        patch.setDateOfExit(LocalDate.of(2040, 1, 1));

        EmpRowData row = flow.update(orgId, id, patch);

        assertEquals("NEW-25", row.getEmployeeCode());
        assertEquals("Lead", row.getDesignation());
        assertEquals("INTERN", row.getEmployeeType());
        assertEquals(LocalDate.of(2019, 1, 1), row.getDateOfJoining());
        assertEquals(LocalDate.of(2040, 1, 1), row.getDateOfExit());
    }

    @Test
    public void deactivate_revokesSessions() {
        Long orgId = anOrg("acme-hrc-26");
        Long id = flow.create(orgId, form("rev26@acme.test", UserRole.EMPLOYEE));

        flow.deactivate(id);

        assertNotNull(userRepo.findById(id).orElseThrow().getTokensValidAfter());
    }

    /* Private Helper Methods */

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

    private static EmpForm status(String value) {
        EmpForm f = new EmpForm();
        f.setStatus(value);
        return f;
    }

    private static UserInviteForm invite(String name, String email) {
        UserInviteForm f = new UserInviteForm();
        f.setName(name);
        f.setEmail(email);
        f.setRole(UserRole.EMPLOYEE);
        return f;
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
