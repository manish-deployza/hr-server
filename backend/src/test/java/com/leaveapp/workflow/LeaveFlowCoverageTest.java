package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.LeaveActionForm;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.NotificationPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.LeaveRequestRepo;
import com.leaveapp.repo.LeaveTypeRepo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.NotificationService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

// Branch-level coverage for LeaveFlow beyond LeaveFlowTest: apply() validation arms,
// overlap boundaries, HR-routed types, act()/cancel() guards and notification side effects.
public class LeaveFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveFlow leaveFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private NotificationService notifSvc;

    @Autowired
    private LeaveTypeRepo typeRepo;

    @Autowired
    private LeaveRequestRepo requestRepo;

    /* Public Methods */

    @Test
    public void leaveTypes_filtersByEmployeeType() {
        Fixture f = fixture("cov-lv-1");
        LeaveTypePojo contractOnly = leaveType(f.orgId, "CO", new BigDecimal("5"));
        contractOnly.setApplicableType("CONTRACT");
        typeRepo.save(contractOnly);

        List<Long> ids = leaveFlow.leaveTypes(f.reportUserId).stream().map(t -> t.getId()).toList();

        assertTrue(ids.contains(f.leaveTypeId));
        assertTrue(!ids.contains(contractOnly.getId()));
    }

    @Test
    public void myBalances_listsApplicableTypes() {
        Fixture f = fixture("cov-lv-2");

        var balances = leaveFlow.myBalances(f.reportUserId);

        assertEquals(1, balances.size());
        assertEquals(0, new BigDecimal("12.0").compareTo(balances.get(0).getBalance()));
    }

    @Test
    public void apply_missingLeaveType_badRequest() {
        Fixture f = fixture("cov-lv-3");
        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(null, nextMonday(), nextMonday())));
    }

    @Test
    public void apply_missingStartDate_badRequest() {
        Fixture f = fixture("cov-lv-4");
        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, null, nextMonday())));
    }

    @Test
    public void apply_missingEndDate_badRequest() {
        Fixture f = fixture("cov-lv-5");
        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), null)));
    }

    @Test
    public void apply_startAfterEnd_badRequest() {
        Fixture f = fixture("cov-lv-6");
        assertThrows(BadRequestException.class, () -> leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, nextMonday().plusDays(1), nextMonday())));
    }

    @Test
    public void apply_startInPast_badRequest() {
        Fixture f = fixture("cov-lv-7");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, yesterday, yesterday)));
    }

    @Test
    public void apply_insideNoticeWindow_badRequest() {
        Fixture f = fixture("cov-lv-8");
        LeaveTypePojo type = typeRepo.findById(f.leaveTypeId).orElseThrow();
        type.setNoticeDays(60);
        typeRepo.save(type);

        assertThrows(BadRequestException.class, () -> leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, nextMonday(), nextMonday())));
    }

    @Test
    public void apply_exactlyOnNoticeBoundary_isAccepted() {
        Fixture f = fixture("cov-lv-9");
        LocalDate start = nextMonday();
        LeaveTypePojo type = typeRepo.findById(f.leaveTypeId).orElseThrow();
        type.setNoticeDays((int) ChronoUnit.DAYS.between(LocalDate.now(), start));
        typeRepo.save(type);

        var result = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, start, start));

        assertEquals("PENDING_MANAGER", result.getStatus());
    }

    @Test
    public void apply_unknownLeaveType_badRequest() {
        Fixture f = fixture("cov-lv-10");
        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(-1L, nextMonday(), nextMonday())));
    }

    @Test
    public void apply_inactiveLeaveType_badRequest() {
        Fixture f = fixture("cov-lv-11");
        LeaveTypePojo type = typeRepo.findById(f.leaveTypeId).orElseThrow();
        type.setActive(false);
        typeRepo.save(type);

        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())));
    }

    @Test
    public void apply_leaveTypeOfOtherOrg_badRequest() {
        Fixture f = fixture("cov-lv-12");
        Fixture other = fixture("cov-lv-12b");
        assertThrows(BadRequestException.class, () -> leaveFlow.apply(f.reportUserId,
            applyForm(other.leaveTypeId, nextMonday(), nextMonday())));
    }

    @Test
    public void apply_onlySundayInRange_noWorkingDays() {
        Fixture f = fixture("cov-lv-13");
        LocalDate sunday = nextMonday().minusDays(1);
        assertThrows(BadRequestException.class,
            () -> leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, sunday, sunday)));
    }

    @Test
    public void apply_overlapBoundaries_adjacentRangesAllowedTouchingConflicts() {
        Fixture f = fixture("cov-lv-14");
        LocalDate mon = nextMonday();
        leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon.plusDays(1), mon.plusDays(2)));

        // ends the day before existing starts: no overlap
        leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon, mon));
        // starts the day after existing ends: no overlap
        leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon.plusDays(3), mon.plusDays(3)));
        // touches the last day of existing: overlap
        assertThrows(ConflictException.class, () -> leaveFlow.apply(f.reportUserId,
            applyForm(f.leaveTypeId, mon.plusDays(2), mon.plusDays(2))));
    }

    @Test
    public void apply_overlapWithCancelledRequest_isAllowed() {
        Fixture f = fixture("cov-lv-15");
        LocalDate mon = nextMonday();
        Long first = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon, mon)).getId();
        leaveFlow.cancel(f.reportUserId, first);

        var again = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon, mon));

        assertEquals("PENDING_MANAGER", again.getStatus());
    }

    @Test
    public void apply_hrRoutedType_pendingHr_notifiesHrUsers() {
        Fixture f = fixture("cov-lv-16");
        LeaveTypePojo type = typeRepo.findById(f.leaveTypeId).orElseThrow();
        type.setApproverRole(ApproverRole.HR);
        typeRepo.save(type);

        var result = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday()));

        assertEquals("PENDING_HR", result.getStatus());
        assertEquals(ApproverRole.HR, leaveFlow.pendingApproverRole(result.getId()));
        assertEquals(1, notifSvc.listForUser(f.managerUserId, true).size()); // manager fixture is an HR user
    }

    @Test
    public void myRequests_recentTrimsToThree_otherwiseAll() {
        Fixture f = fixture("cov-lv-17");
        LocalDate mon = nextMonday();
        for (int i = 0; i < 4; i++) {
            LocalDate d = mon.plusWeeks(i);
            leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, d, d));
        }

        assertEquals(3, leaveFlow.myRequests(f.reportUserId, true).size());
        assertEquals(4, leaveFlow.myRequests(f.reportUserId, false).size());
    }

    @Test
    public void myRequests_recentWithFewRequests_returnsAll() {
        Fixture f = fixture("cov-lv-18");
        leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday()));

        assertEquals(1, leaveFlow.myRequests(f.reportUserId, true).size());
    }

    @Test
    public void pendingApproverRole_manager_andApplicantUserId() {
        Fixture f = fixture("cov-lv-19");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();

        assertEquals(ApproverRole.MANAGER, leaveFlow.pendingApproverRole(id));
        assertEquals(f.reportUserId, leaveFlow.applicantUserId(id));
    }

    @Test
    public void pendingApproverRole_whenAlreadyResolved_badRequest() {
        Fixture f = fixture("cov-lv-20");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();
        leaveFlow.cancel(f.reportUserId, id);

        assertThrows(BadRequestException.class, () -> leaveFlow.pendingApproverRole(id));
    }

    @Test
    public void getRequest_unknownId_notFound() {
        assertThrows(NotFoundException.class, () -> leaveFlow.getRequest(-1L));
    }

    @Test
    public void act_pendingOrNullAction_badRequest() {
        Fixture f = fixture("cov-lv-21");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();

        assertThrows(BadRequestException.class,
            () -> leaveFlow.act(f.managerUserId, id, action(ApprovalAction.PENDING, null)));
        assertThrows(BadRequestException.class,
            () -> leaveFlow.act(f.managerUserId, id, action(null, null)));
    }

    @Test
    public void act_onResolvedRequest_badRequest() {
        Fixture f = fixture("cov-lv-22");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();
        leaveFlow.act(f.managerUserId, id, action(ApprovalAction.APPROVED, null));

        assertThrows(BadRequestException.class,
            () -> leaveFlow.act(f.managerUserId, id, action(ApprovalAction.REJECTED, null)));
    }

    @Test
    public void act_unknownRequest_notFound() {
        Fixture f = fixture("cov-lv-23");
        assertThrows(NotFoundException.class,
            () -> leaveFlow.act(f.managerUserId, -1L, action(ApprovalAction.APPROVED, null)));
    }

    @Test
    public void act_rejectWithComments_includesCommentsInNotification() {
        Fixture f = fixture("cov-lv-24");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();

        leaveFlow.act(f.managerUserId, id, action(ApprovalAction.REJECTED, "busy season"));

        NotificationPojo n = notifSvc.listForUser(f.reportUserId, false).stream()
            .filter(x -> x.getType() == NotificationType.LEAVE_REJECTED).findFirst().orElseThrow();
        assertTrue(n.getMessage().endsWith(" Comments: busy season"));
        assertEquals("busy season", leaveFlow.getRequest(id).getApprovals().get(0).getComments());
    }

    @Test
    public void act_rejectWithNullOrBlankComments_omitsCommentsSuffix() {
        Fixture f = fixture("cov-lv-25");
        LocalDate mon = nextMonday();
        Long a = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon, mon)).getId();
        Long b = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, mon.plusWeeks(1), mon.plusWeeks(1))).getId();

        leaveFlow.act(f.managerUserId, a, action(ApprovalAction.REJECTED, null));
        leaveFlow.act(f.managerUserId, b, action(ApprovalAction.REJECTED, "   "));

        List<NotificationPojo> all = notifSvc.listForUser(f.reportUserId, false).stream()
            .filter(n -> n.getType() == NotificationType.LEAVE_REJECTED).toList();
        assertEquals(2, all.size());
        assertTrue(all.stream().noneMatch(n -> n.getMessage().contains("Comments:")));
    }

    @Test
    public void cancel_unknownRequest_notFound() {
        Fixture f = fixture("cov-lv-26");
        assertThrows(NotFoundException.class, () -> leaveFlow.cancel(f.reportUserId, -1L));
    }

    @Test
    public void cancel_otherUsersRequest_notFound() {
        Fixture f = fixture("cov-lv-27");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();

        assertThrows(NotFoundException.class, () -> leaveFlow.cancel(f.managerUserId, id));
    }

    @Test
    public void cancel_approvedRequest_badRequest() {
        Fixture f = fixture("cov-lv-28");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();
        leaveFlow.act(f.managerUserId, id, action(ApprovalAction.APPROVED, null));

        assertThrows(BadRequestException.class, () -> leaveFlow.cancel(f.reportUserId, id));
    }

    @Test
    public void cancel_managerRouted_notifiesManager() {
        Fixture f = fixture("cov-lv-29");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();

        leaveFlow.cancel(f.reportUserId, id);

        assertEquals(1, cancelledNotifications(f.managerUserId));
    }

    @Test
    public void cancel_hrRouted_notifiesHrUsers() {
        Fixture f = fixture("cov-lv-30");
        LeaveTypePojo type = typeRepo.findById(f.leaveTypeId).orElseThrow();
        type.setApproverRole(ApproverRole.HR);
        typeRepo.save(type);
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();

        leaveFlow.cancel(f.reportUserId, id);

        assertEquals(1, cancelledNotifications(f.managerUserId));
        assertEquals(LeaveStatus.CANCELLED, requestRepo.findById(id).orElseThrow().getStatus());
    }

    @Test
    public void cancel_managerRoutedWithoutManager_noNotification() {
        // Forced back to PENDING_MANAGER for a manager-less employee (apply would auto-approve it).
        Fixture f = fixture("cov-lv-31");
        UserPojo loner = user(f.orgId, "loner@cov-lv-31.test", UserRole.EMPLOYEE);
        employee(loner, null);
        Long id = leaveFlow.apply(loner.getId(), applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();
        LeaveRequestPojo req = requestRepo.findById(id).orElseThrow();
        req.setStatus(LeaveStatus.PENDING_MANAGER);
        requestRepo.save(req);

        leaveFlow.cancel(loner.getId(), id);

        assertEquals(LeaveStatus.CANCELLED, requestRepo.findById(id).orElseThrow().getStatus());
        assertEquals(0, cancelledNotifications(f.managerUserId));
    }

    @Test
    public void getRequest_hrImposedAndCancelled_exposesFlagsAndCancelledAt() {
        Fixture f = fixture("cov-lv-32");
        Long id = leaveFlow.apply(f.reportUserId, applyForm(f.leaveTypeId, nextMonday(), nextMonday())).getId();
        LeaveRequestPojo req = requestRepo.findById(id).orElseThrow();
        req.setHrImposed(true);
        requestRepo.save(req);
        leaveFlow.cancel(f.reportUserId, id);

        LeaveRequestData detail = leaveFlow.getRequest(id);

        assertEquals(Boolean.TRUE, detail.getIsHrImposed());
        assertNotNull(detail.getCancelledAt());
        assertEquals(Boolean.TRUE, leaveFlow.myRequests(f.reportUserId, false).get(0).getIsHrImposed());
    }

    /* Private Helper Methods */

    private long cancelledNotifications(Long userId) {
        return notifSvc.listForUser(userId, false).stream()
            .filter(n -> n.getType() == NotificationType.LEAVE_CANCELLED).count();
    }

    private Fixture fixture(String orgKey) {
        Long orgId = orgSvc.create("Acme", orgKey, "hr@" + orgKey + ".test", "IN").getId();
        UserPojo manager = user(orgId, "mgr@" + orgKey + ".test", UserRole.HR);
        employee(manager, null);
        UserPojo report = user(orgId, "emp@" + orgKey + ".test", UserRole.EMPLOYEE);
        employee(report, manager.getId());
        LeaveTypePojo cl = leaveType(orgId, "CL", new BigDecimal("12"));
        return new Fixture(orgId, manager.getId(), report.getId(), cl.getId());
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

    private static LeaveActionForm action(ApprovalAction action, String comments) {
        LeaveActionForm form = new LeaveActionForm();
        form.setAction(action);
        form.setComments(comments);
        return form;
    }

    // A Monday ~2 weeks out: comfortably in the future.
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
