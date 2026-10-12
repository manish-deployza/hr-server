package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.data.LeaveBalanceData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.form.LeaveActionForm;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.model.form.LeaveTypeForm;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.repo.LeaveRequestRepo;
import com.leaveapp.repo.LeaveTypeRepo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.LeaveBalanceService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

// Covers the leave-request lifecycle: working-day count, approval-chain build,
// manager/HR action state machine, ledger debit, overlap + cancel guards.
public class LeaveFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveFlow leaveFlow;

    @Autowired
    private HrLeaveFlow hrLeaveFlow;

    @Autowired
    private LeaveBalanceService balanceSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private LeaveTypeRepo typeRepo;

    @Autowired
    private LeaveRequestRepo requestRepo;

    /* Public Methods */

    @Test
    public void apply_buildsPendingManagerRequest_countingOnlyWorkingDays() {
        Fixture f = fixture("acme-lv-1", 14);
        LeaveApplyForm form = applyForm(f.leaveTypeId, nextMonday(), nextMonday().plusDays(4)); // Mon..Fri

        var result = leaveFlow.apply(f.reportUserId, form);

        LeaveRequestPojo req = requestRepo.findById(result.getId()).orElseThrow();
        assertEquals("PENDING_MANAGER", req.getStatus().name());
        assertEquals(0, new BigDecimal("5.0").compareTo(req.getNumDays()));

        LeaveRequestData detail = leaveFlow.getRequest(req.getId());
        assertEquals(1, detail.getApprovals().size());
        assertEquals("MANAGER", detail.getApprovals().get(0).getApproverRole().name());
        assertEquals("PENDING", detail.getApprovals().get(0).getAction().name());
    }

    @Test
    public void managerApprove_marksApproved_andDebitsLedger() {
        Fixture f = fixture("acme-lv-2", 14);
        Long reqId = leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, nextMonday(), nextMonday().plusDays(4))).getId();

        leaveFlow.act(f.managerUserId, reqId, action("APPROVED"));

        assertEquals("APPROVED", requestRepo.findById(reqId).orElseThrow().getStatus().name());
        List<LeaveBalanceData> balances = leaveFlow.myBalances(f.reportUserId);
        BigDecimal cl = balances.stream()
            .filter(b -> b.getLeaveTypeId().equals(f.leaveTypeId))
            .findFirst().orElseThrow().getBalance();
        assertEquals(0, new BigDecimal("9.0").compareTo(cl)); // 14 accrued - 5 taken
    }

    @Test
    public void managerReject_marksRejected_noLedgerDebit() {
        Fixture f = fixture("acme-lv-3", 14);
        Long reqId = leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, nextMonday(), nextMonday().plusDays(4))).getId();

        leaveFlow.act(f.managerUserId, reqId, action("REJECTED"));

        assertEquals("REJECTED", requestRepo.findById(reqId).orElseThrow().getStatus().name());
        // no DEBIT written -- ledger untouched by a rejection
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceSvc.balance(
            f.reportUserId, f.leaveTypeId, LocalDate.now().getYear())));
    }

    @Test
    public void overlappingDates_conflict() {
        Fixture f = fixture("acme-lv-4", 14);
        leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday().plusDays(4)));

        assertThrows(ConflictException.class, () -> leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, nextMonday().plusDays(2), nextMonday().plusDays(6))));
    }

    @Test
    public void cancel_byOwner_marksCancelled() {
        Fixture f = fixture("acme-lv-5", 14);
        Long reqId = leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, nextMonday(), nextMonday().plusDays(4))).getId();

        leaveFlow.cancel(f.reportUserId, reqId);

        assertEquals("CANCELLED", requestRepo.findById(reqId).orElseThrow().getStatus().name());
    }

    @Test
    public void apply_whenEmployeeHasNoManager_autoApproves() {
        Fixture f = fixture("acme-lv-6", 14);
        UserPojo loner = user(f.orgId, "loner@acme.test", "EMPLOYEE");
        employee(loner, null, "REGULAR");

        var result = leaveFlow.apply(loner.getId(),
            applyForm(f.leaveTypeId, nextMonday(), nextMonday().plusDays(4)));

        assertEquals("APPROVED", result.getStatus());
    }

    // act_byNonManager_isForbidden moved: who-may-act is now authorized by
    // LeaveController (see LeaveControllerTest), not LeaveFlow.

    @Test
    public void hrCreateLeaveType_thenListed() {
        Fixture f = fixture("acme-lv-8", 14);
        LeaveTypeForm form = new LeaveTypeForm();
        form.setName("Marriage Leave");
        form.setDaysPerYear("5 days/year");
        form.setRequiresAdvanceNoticeDays(30);

        hrLeaveFlow.createLeaveType(f.orgId(), form);

        List<LeaveTypeData> types = hrLeaveFlow.leaveTypes(f.orgId());
        LeaveTypeData created = types.stream()
            .filter(t -> "MARRIAGE_LEAVE".equals(t.getCode())).findFirst().orElseThrow();
        assertEquals("5 days/year", created.getDaysPerYear());
        assertEquals(30, created.getRequiresAdvanceNoticeDays());
        assertTrue(f.leaveTypeId > 0);
    }

    /* Private Helper Methods */

    private Fixture fixture(String orgKey, int clDaysPerYear) {
        Long orgId = orgSvc.create("Acme", orgKey, "hr@" + orgKey + ".test", "IN").getId();
        UserPojo manager = user(orgId, "mgr@" + orgKey + ".test", "HR");
        employee(manager, null, "REGULAR");
        UserPojo report = user(orgId, "emp@" + orgKey + ".test", "EMPLOYEE");
        employee(report, manager.getId(), "REGULAR");
        LeaveTypePojo cl = leaveType(orgId, "CL", clDaysPerYear > 0 ? new BigDecimal(clDaysPerYear) : null);
        return new Fixture(orgId, manager.getId(), report.getId(), cl.getId());
    }

    private UserPojo user(Long orgId, String email, String role) {
        return userSvc.createUser(orgId, email, "s3cretPass!".getBytes(StandardCharsets.UTF_8), UserRole.valueOf(role));
    }

    private void employee(UserPojo user, Long managerUserId, String employeeType) {
        EmpPojo emp = new EmpPojo();
        emp.setStatus(EmpStatus.ACTIVE);
        emp.setUserId(user.getId());
        emp.setOrgId(user.getOrgId());
        emp.setEmployeeCode("E-" + user.getId());
        emp.setName("Emp " + user.getId());
        emp.setDateOfJoining(LocalDate.now().minusYears(2));
        emp.setEmployeeType(employeeType);
        emp.setManagerId(managerUserId);
        empSvc.createProfile(emp);
    }

    private LeaveTypePojo leaveType(Long orgId, String code, BigDecimal daysPerYear) {
        Instant now = Instant.now();
        LeaveTypePojo t = new LeaveTypePojo();
        t.setOrgId(orgId);
        t.setCode(code);
        t.setName(code + " Leave");
        t.setApproverRole(ApproverRole.MANAGER);
        t.setAccrualStrategy(AccrualStrategy.ANNUAL_PRORATA);
        t.setEncashable(false);
        t.setActive(true);
        t.setDaysPerYear(daysPerYear);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return typeRepo.save(t);
    }

    private static LeaveApplyForm applyForm(Long leaveTypeId, LocalDate start, LocalDate end) {
        LeaveApplyForm form = new LeaveApplyForm();
        form.setLeaveTypeId(leaveTypeId);
        form.setStartDate(start);
        form.setEndDate(end);
        form.setReason("test");
        return form;
    }

    private static LeaveActionForm action(String action) {
        LeaveActionForm form = new LeaveActionForm();
        form.setAction(ApprovalAction.valueOf(action));
        return form;
    }

    // A Monday ~2 weeks out: comfortably in the future, no weekend in the Mon..Fri span.
    private static LocalDate nextMonday() {
        LocalDate d = LocalDate.now().plusDays(14);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private record Fixture(Long orgId, Long managerUserId, Long reportUserId, Long leaveTypeId) {
    }
}
