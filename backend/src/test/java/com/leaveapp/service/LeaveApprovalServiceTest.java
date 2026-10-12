package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.LeaveTypeRepo;

// The approval decision + status state machine, driven directly at the service level:
// notice-days rule, overlap guard, approver routing, auto-approve, approve/cancel side effects.
public class LeaveApprovalServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveApprovalService approvalSvc;

    @Autowired
    private LeaveRequestService requestSvc;

    @Autowired
    private LeaveBalanceService balanceSvc;

    @Autowired
    private NotificationService notifSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private LeaveTypeRepo typeRepo;

    /* Public Methods */

    @Test
    public void validateApplication_startInPast_badRequest() {
        LeaveTypePojo type = new LeaveTypePojo();

        assertThrows(BadRequestException.class,
            () -> approvalSvc.validateApplication(type, LocalDate.now().minusDays(1)));
    }

    @Test
    public void validateApplication_todayWithNoNoticeRule_ok() {
        LeaveTypePojo type = new LeaveTypePojo();

        assertDoesNotThrow(() -> approvalSvc.validateApplication(type, LocalDate.now()));
    }

    @Test
    public void validateApplication_noticeBoundary_exactDaysOkOneShortFails() {
        LeaveTypePojo type = new LeaveTypePojo();
        type.setNoticeDays(5);

        assertDoesNotThrow(() -> approvalSvc.validateApplication(type, LocalDate.now().plusDays(5)));
        assertThrows(BadRequestException.class,
            () -> approvalSvc.validateApplication(type, LocalDate.now().plusDays(4)));
    }

    @Test
    public void validateApplication_zeroNoticeToday_ok() {
        LeaveTypePojo type = new LeaveTypePojo();
        type.setNoticeDays(0);

        assertDoesNotThrow(() -> approvalSvc.validateApplication(type, LocalDate.now()));
    }

    @Test
    public void requireNoOverlap_ignoresTerminalStatuses_andNonTouchingRanges() {
        Fixture f = fixture("appr-1");
        LocalDate d = LocalDate.now().plusDays(30);
        saveRequest(f, d, d.plusDays(2), LeaveStatus.REJECTED);
        saveRequest(f, d, d.plusDays(2), LeaveStatus.CANCELLED);
        saveRequest(f, d, d.plusDays(2), LeaveStatus.LAPSED);
        saveRequest(f, d.plusDays(10), d.plusDays(12), LeaveStatus.APPROVED); // starts after the query end
        saveRequest(f, d.minusDays(10), d.minusDays(8), LeaveStatus.APPROVED); // ends before the query start

        assertDoesNotThrow(() -> approvalSvc.requireNoOverlap(f.reportId, d, d.plusDays(2)));
    }

    @Test
    public void requireNoOverlap_activeOverlap_conflict_includingSingleDayEdges() {
        Fixture f = fixture("appr-2");
        LocalDate d = LocalDate.now().plusDays(30);
        saveRequest(f, d, d.plusDays(2), LeaveStatus.PENDING_MANAGER);

        assertThrows(ConflictException.class, () -> approvalSvc.requireNoOverlap(f.reportId, d.minusDays(3), d));
        assertThrows(ConflictException.class,
            () -> approvalSvc.requireNoOverlap(f.reportId, d.plusDays(2), d.plusDays(5)));
        assertThrows(ConflictException.class,
            () -> approvalSvc.requireNoOverlap(f.reportId, d.plusDays(1), d.plusDays(1)));
    }

    @Test
    public void assignApprover_nullRole_defaultsToManager_andNotifiesManager() {
        Fixture f = fixture("appr-3");
        LeaveRequestPojo req = saveRequest(f, LocalDate.now().plusDays(30), LocalDate.now().plusDays(30),
            LeaveStatus.APPLIED);
        LeaveTypePojo type = typeRepo.findById(f.typeId).orElseThrow();

        approvalSvc.assignApprover(req, type, empSvc.findByUserId(f.reportId));

        assertEquals(LeaveStatus.PENDING_MANAGER, req.getStatus());
        assertEquals(ApproverRole.MANAGER, approvalSvc.currentStep(req).getApproverRole());
        assertEquals(ApprovalAction.PENDING, approvalSvc.currentStep(req).getAction());
        assertEquals(1, notifSvc.listForUser(f.managerId, false).size());
    }

    @Test
    public void assignApprover_managerRoleWithoutManager_noNotification() {
        Fixture f = fixture("appr-4");
        LeaveRequestPojo req = saveRequest(f, LocalDate.now().plusDays(30), LocalDate.now().plusDays(30),
            LeaveStatus.APPLIED);
        LeaveTypePojo type = typeRepo.findById(f.typeId).orElseThrow();
        EmpPojo orphan = applicant(f.orgId, "Orphan", null);

        approvalSvc.assignApprover(req, type, orphan);

        assertEquals(LeaveStatus.PENDING_MANAGER, req.getStatus());
        assertTrue(notifSvc.listForUser(f.managerId, false).isEmpty());
    }

    @Test
    public void assignApprover_hrRole_pendingHr_notifiesEveryHrUser_andFallsBackToGenericName() {
        Fixture f = fixture("appr-5");
        UserPojo admin = user(f.orgId, "admin@appr-5.test", UserRole.ORG_ADMIN);
        LeaveTypePojo type = typeRepo.findById(f.typeId).orElseThrow();
        type.setApproverRole(ApproverRole.HR);
        LeaveRequestPojo req = saveRequest(f, LocalDate.now().plusDays(30), LocalDate.now().plusDays(30),
            LeaveStatus.APPLIED);

        approvalSvc.assignApprover(req, type, applicant(f.orgId, null, f.managerId));

        assertEquals(LeaveStatus.PENDING_HR, req.getStatus());
        assertEquals(ApproverRole.HR, approvalSvc.currentStep(req).getApproverRole());
        NotificationPojo n = notifSvc.listForUser(admin.getId(), false).get(0);
        assertTrue(n.getMessage().startsWith("An employee applied"));
        assertEquals(1, notifSvc.listForUser(f.managerId, false).size()); // manager fixture user is HR
    }

    @Test
    public void assignApprover_blankApplicantName_usesGenericName() {
        Fixture f = fixture("appr-6");
        LeaveTypePojo type = typeRepo.findById(f.typeId).orElseThrow();
        LeaveRequestPojo req = saveRequest(f, LocalDate.now().plusDays(30), LocalDate.now().plusDays(30),
            LeaveStatus.APPLIED);

        approvalSvc.assignApprover(req, type, applicant(f.orgId, "  ", f.managerId));

        assertTrue(notifSvc.listForUser(f.managerId, false).get(0).getMessage().startsWith("An employee applied"));
    }

    @Test
    public void assignApprover_namedApplicant_usesName() {
        Fixture f = fixture("appr-7");
        LeaveTypePojo type = typeRepo.findById(f.typeId).orElseThrow();
        LeaveRequestPojo req = saveRequest(f, LocalDate.now().plusDays(30), LocalDate.now().plusDays(30),
            LeaveStatus.APPLIED);

        approvalSvc.assignApprover(req, type, applicant(f.orgId, "Asha", f.managerId));

        assertTrue(notifSvc.listForUser(f.managerId, false).get(0).getMessage().startsWith("Asha applied"));
    }

    @Test
    public void settleUnreachableApprover_pendingManagerWithoutManager_autoApprovesAndDebits() {
        Fixture f = fixture("appr-8");
        LocalDate d = LocalDate.now().plusDays(30);
        LeaveRequestPojo req = pendingRequest(f, d, LeaveStatus.PENDING_MANAGER);

        approvalSvc.settleUnreachableApprover(req, applicant(f.orgId, "Solo", null), f.reportId);

        assertEquals(LeaveStatus.APPROVED, req.getStatus());
        assertEquals("auto-approved: no manager assigned", approvalSvc.currentStep(req).getComments());
        assertEquals(0, new BigDecimal("-2.0").compareTo(balanceSvc.balance(f.reportId, f.typeId, d.getYear())));
    }

    @Test
    public void settleUnreachableApprover_pendingManagerWithManager_leftAlone() {
        Fixture f = fixture("appr-9");
        LeaveRequestPojo req = pendingRequest(f, LocalDate.now().plusDays(30), LeaveStatus.PENDING_MANAGER);

        approvalSvc.settleUnreachableApprover(req, applicant(f.orgId, "Has Mgr", f.managerId), f.reportId);

        assertEquals(LeaveStatus.PENDING_MANAGER, req.getStatus());
    }

    @Test
    public void settleUnreachableApprover_pendingHrWithoutManager_leftAlone() {
        Fixture f = fixture("appr-10");
        LeaveRequestPojo req = pendingRequest(f, LocalDate.now().plusDays(30), LeaveStatus.PENDING_HR);

        approvalSvc.settleUnreachableApprover(req, applicant(f.orgId, "Solo", null), f.reportId);

        assertEquals(LeaveStatus.PENDING_HR, req.getStatus());
    }

    @Test
    public void currentStep_withoutApprovalRow_badRequest() {
        Fixture f = fixture("appr-11");
        LeaveRequestPojo req = saveRequest(f, LocalDate.now().plusDays(30), LocalDate.now().plusDays(30),
            LeaveStatus.APPLIED);

        assertThrows(BadRequestException.class, () -> approvalSvc.currentStep(req));
    }

    @Test
    public void approve_stampsStep_debitsLedger_notifiesApplicant() {
        Fixture f = fixture("appr-12");
        LocalDate d = LocalDate.now().plusDays(30);
        LeaveRequestPojo req = pendingRequest(f, d, LeaveStatus.PENDING_MANAGER);

        approvalSvc.approve(req, empSvc.findByUserId(f.reportId), f.managerId, "go ahead");

        LeaveRequestApprovalPojo step = approvalSvc.currentStep(req);
        assertEquals(ApprovalAction.APPROVED, step.getAction());
        assertEquals(f.managerId, step.getApproverUserId());
        assertEquals("go ahead", step.getComments());
        assertNotNull(step.getActedAt());
        assertEquals(LeaveStatus.APPROVED, req.getStatus());
        assertEquals(0, new BigDecimal("-2.0").compareTo(balanceSvc.balance(f.reportId, f.typeId, d.getYear())));
        List<NotificationPojo> inbox = notifSvc.listForUser(f.reportId, false);
        assertEquals(NotificationType.LEAVE_APPROVED, inbox.get(0).getType());
    }

    @Test
    public void notifyCancelled_managerRouted_notifiesManager() {
        Fixture f = fixture("appr-13");
        LeaveRequestPojo req = pendingRequest(f, LocalDate.now().plusDays(30), LeaveStatus.PENDING_MANAGER);

        approvalSvc.notifyCancelled(req, empSvc.findByUserId(f.reportId));

        assertEquals(NotificationType.LEAVE_CANCELLED, notifSvc.listForUser(f.managerId, false).get(0).getType());
    }

    @Test
    public void notifyCancelled_managerRoutedWithoutManager_silent() {
        Fixture f = fixture("appr-14");
        LeaveRequestPojo req = pendingRequest(f, LocalDate.now().plusDays(30), LeaveStatus.PENDING_MANAGER);

        approvalSvc.notifyCancelled(req, applicant(f.orgId, "Solo", null));

        assertTrue(notifSvc.listForUser(f.managerId, false).isEmpty());
    }

    @Test
    public void notifyCancelled_hrRouted_notifiesHrUsersNotManagerPath() {
        Fixture f = fixture("appr-15");
        LeaveRequestPojo req = pendingRequest(f, LocalDate.now().plusDays(30), LeaveStatus.PENDING_HR);
        req.setStatus(LeaveStatus.PENDING_HR);
        LeaveRequestApprovalPojo step = approvalSvc.currentStep(req);
        step.setApproverRole(ApproverRole.HR);

        approvalSvc.notifyCancelled(req, applicant(f.orgId, "Asha", null));

        assertEquals(1, notifSvc.listForUser(f.managerId, false).size()); // the HR fixture user
    }

    @Test
    public void stampStep_setsAllDecisionFields() {
        LeaveRequestApprovalPojo step = new LeaveRequestApprovalPojo();

        approvalSvc.stampStep(step, ApprovalAction.REJECTED, 7L, "no");

        assertEquals(ApprovalAction.REJECTED, step.getAction());
        assertEquals(7L, step.getApproverUserId());
        assertEquals("no", step.getComments());
        assertNotNull(step.getActedAt());
    }

    /* Private Helper Methods */

    // A saved request (2 days: d..d+1) with its single PENDING approval row.
    private LeaveRequestPojo pendingRequest(Fixture f, LocalDate d, LeaveStatus status) {
        LeaveRequestPojo req = saveRequest(f, d, d.plusDays(1), status);
        LeaveRequestApprovalPojo approval = new LeaveRequestApprovalPojo();
        approval.setLeaveRequestId(req.getId());
        approval.setApproverRole(ApproverRole.MANAGER);
        approval.setAction(ApprovalAction.PENDING);
        approval.setCreatedAt(Instant.now());
        requestSvc.saveApproval(approval);
        return req;
    }

    private LeaveRequestPojo saveRequest(Fixture f, LocalDate start, LocalDate end, LeaveStatus status) {
        Instant now = Instant.now();
        LeaveRequestPojo req = new LeaveRequestPojo();
        req.setUserId(f.reportId);
        req.setLeaveTypeId(f.typeId);
        req.setStartDate(start);
        req.setEndDate(end);
        req.setNumDays(new BigDecimal("2.0"));
        req.setReason("test");
        req.setHrImposed(false);
        req.setStatus(status);
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        return requestSvc.save(req);
    }

    // In-memory applicant (not persisted) for the paths that only read name/org/manager.
    private static EmpPojo applicant(Long orgId, String name, Long managerId) {
        EmpPojo emp = new EmpPojo();
        emp.setStatus(EmpStatus.ACTIVE);
        emp.setOrgId(orgId);
        emp.setName(name);
        emp.setManagerId(managerId);
        return emp;
    }

    private Fixture fixture(String orgKey) {
        Long orgId = orgSvc.create("Acme", orgKey, "hr@" + orgKey + ".test", "IN").getId();
        UserPojo manager = user(orgId, "mgr@" + orgKey + ".test", UserRole.HR);
        employee(manager, null);
        UserPojo report = user(orgId, "emp@" + orgKey + ".test", UserRole.EMPLOYEE);
        employee(report, manager.getId());
        Instant now = Instant.now();
        LeaveTypePojo t = new LeaveTypePojo();
        t.setOrgId(orgId);
        t.setCode("CL");
        t.setName("CL Leave");
        t.setApproverRole(ApproverRole.MANAGER);
        t.setAccrualStrategy(AccrualStrategy.ANNUAL_PRORATA);
        t.setEncashable(false);
        t.setActive(true);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return new Fixture(orgId, manager.getId(), report.getId(), typeRepo.save(t).getId());
    }

    private UserPojo user(Long orgId, String email, UserRole role) {
        return userSvc.createUser(orgId, email, "s3cretPass!".getBytes(StandardCharsets.UTF_8), role);
    }

    private void employee(UserPojo user, Long managerUserId) {
        EmpPojo emp = new EmpPojo();
        emp.setStatus(EmpStatus.ACTIVE);
        emp.setUserId(user.getId());
        emp.setOrgId(user.getOrgId());
        emp.setEmployeeCode("E-" + user.getId());
        emp.setName("Emp " + user.getId());
        emp.setDateOfJoining(LocalDate.now().minusYears(2));
        emp.setEmployeeType("REGULAR");
        emp.setManagerId(managerUserId);
        empSvc.createProfile(emp);
    }

    private record Fixture(Long orgId, Long managerId, Long reportId, Long typeId) {
    }
}
