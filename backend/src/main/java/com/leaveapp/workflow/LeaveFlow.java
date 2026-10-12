package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB): the approval chain and status
// state machine live in LeaveApprovalService; *Pojo -> *Data mapping lives in LeaveMapper.
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.LeaveApplyData;
import com.leaveapp.model.data.LeaveBalanceData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.form.LeaveActionForm;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.LeaveApprovalService;
import com.leaveapp.service.LeaveBalanceService;
import com.leaveapp.service.LeaveRequestService;
import com.leaveapp.service.LeaveTypeService;
import com.leaveapp.service.NotificationService;
import com.leaveapp.service.PublicHolidayService;

// Employee-facing leave surface: browse types, balances, apply, list/read own requests,
// and the shared approve/reject action. Authorization lives in LeaveController --
// this Flow receives only already-authorized primitives.
@Service
public class LeaveFlow {

    /* Variables */

    private static final int RECENT_LIMIT = 3;
    private static final Set<LeaveStatus> AWAITING = Set.of(LeaveStatus.PENDING_MANAGER, LeaveStatus.PENDING_HR);
    private static final Set<LeaveStatus> CANCELLABLE =
        Set.of(LeaveStatus.APPLIED, LeaveStatus.PENDING_MANAGER, LeaveStatus.PENDING_HR);

    @Autowired
    private LeaveTypeService typeSvc;
    @Autowired
    private LeaveRequestService requestSvc;
    @Autowired
    private LeaveApprovalService approvalSvc;
    @Autowired
    private LeaveBalanceService balanceSvc;
    @Autowired
    private PublicHolidayService holidaySvc;
    @Autowired
    private EmpService employeeSvc;
    @Autowired
    private NotificationService notifSvc;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveTypeData> leaveTypes(Long userId) {
        EmpPojo emp = employeeSvc.findByUserId(userId);
        return typeSvc.listApplicable(emp.getOrgId(), emp.getEmployeeType()).stream()
            .map(LeaveMapper::toTypeData)
            .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public List<LeaveBalanceData> myBalances(Long userId) {
        EmpPojo emp = employeeSvc.findByUserId(userId);
        List<LeaveTypePojo> types = typeSvc.listApplicable(emp.getOrgId(), emp.getEmployeeType());
        Map<Long, BigDecimal> balances = balanceSvc.currentBalances(userId, types, emp.getDateOfJoining());
        return types.stream().map(t -> LeaveMapper.toBalanceData(t, balances.get(t.getId()))).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaveApplyData apply(Long userId, LeaveApplyForm form) {
        if (form.getLeaveTypeId() == null) {
            throw new BadRequestException("leaveTypeId is required");
        }
        if (form.getStartDate() == null || form.getEndDate() == null) {
            throw new BadRequestException("startDate and endDate are required");
        }
        if (form.getStartDate().isAfter(form.getEndDate())) {
            throw new BadRequestException("startDate must be on or before endDate");
        }
        EmpPojo emp = employeeSvc.findByUserId(userId);
        LeaveTypePojo type = typeSvc.findActive(emp.getOrgId(), form.getLeaveTypeId());
        approvalSvc.validateApplication(type, form.getStartDate());
        approvalSvc.requireNoOverlap(userId, form.getStartDate(), form.getEndDate());
        BigDecimal numDays = holidaySvc.workingDaysBetween(emp.getOrgId(), form.getStartDate(), form.getEndDate());
        if (numDays.signum() <= 0) {
            throw new BadRequestException("the selected range contains no working days");
        }
        Instant now = Instant.now();
        LeaveRequestPojo req = new LeaveRequestPojo();
        req.setUserId(userId);
        req.setLeaveTypeId(type.getId());
        req.setStartDate(form.getStartDate());
        req.setEndDate(form.getEndDate());
        req.setNumDays(numDays);
        req.setReason(form.getReason());
        req.setHrImposed(false);
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        req.setStatus(LeaveStatus.APPLIED);
        requestSvc.save(req);
        approvalSvc.assignApprover(req, type, emp);
        approvalSvc.settleUnreachableApprover(req, emp, userId);
        LeaveApplyData data = new LeaveApplyData();
        data.setId(req.getId());
        data.setStatus(req.getStatus().name());
        return data;
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveRequestData> myRequests(Long userId, boolean recent) {
        List<LeaveRequestPojo> rows = new ArrayList<>(requestSvc.findByUser(userId));
        rows.sort(Comparator.comparing(LeaveRequestPojo::getCreatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        if (recent && rows.size() > RECENT_LIMIT) {
            rows = rows.subList(0, RECENT_LIMIT);
        }
        EmpPojo emp = employeeSvc.findByUserId(userId);
        Map<Long, LeaveTypePojo> types = typeSvc.listAll(emp.getOrgId()).stream()
            .collect(Collectors.toMap(LeaveTypePojo::getId, Function.identity()));
        return rows.stream()
            .map(r -> LeaveMapper.toSummary(r, emp, types.get(r.getLeaveTypeId())))
            .toList();
    }

    // Caller is already authorized by LeaveController before this is called.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeaveRequestData getRequest(Long requestId) {
        LeaveRequestPojo req = requestSvc.findById(requestId);
        EmpPojo applicant = employeeSvc.findByUserId(req.getUserId());
        LeaveTypePojo type = typeSvc.findById(applicant.getOrgId(), req.getLeaveTypeId());
        return LeaveMapper.toDetail(req, applicant, type, requestSvc.approvalsOf(requestId));
    }

    // Read-only fact for LeaveController's action-authorization guard.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ApproverRole pendingApproverRole(Long requestId) {
        LeaveRequestPojo req = requestSvc.findById(requestId);
        if (!AWAITING.contains(req.getStatus())) {
            throw new BadRequestException("this request is not awaiting approval");
        }
        return approvalSvc.currentStep(req).getApproverRole();
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public Long applicantUserId(Long requestId) { return requestSvc.findById(requestId).getUserId(); }

    // callerUserId is already authorized (via pendingApproverRole) by LeaveController.
    @Transactional(rollbackFor = Exception.class)
    public void act(Long callerUserId, Long requestId, LeaveActionForm form) {
        ApprovalAction actionEnum = form.getAction();
        if (ApprovalAction.APPROVED != actionEnum && ApprovalAction.REJECTED != actionEnum) {
            throw new BadRequestException("action must be APPROVED or REJECTED");
        }
        LeaveRequestPojo req = requestSvc.findById(requestId);
        if (!AWAITING.contains(req.getStatus())) {
            throw new BadRequestException("this request is not awaiting approval");
        }
        EmpPojo applicant = employeeSvc.findByUserId(req.getUserId());
        if (ApprovalAction.REJECTED == actionEnum) {
            approvalSvc.stampStep(approvalSvc.currentStep(req), actionEnum, callerUserId, form.getComments());
            req.setStatus(LeaveStatus.REJECTED);
            String message = "Your leave request from " + req.getStartDate() + " to " + req.getEndDate()
                + " was rejected."
                + (form.getComments() == null || form.getComments().isBlank() ? "" : " Comments: " + form.getComments());
            notifSvc.notify(req.getUserId(), NotificationType.LEAVE_REJECTED, "Leave request rejected", message,
                EntityType.LEAVE_REQUEST, req.getId());
            return;
        }
        approvalSvc.approve(req, applicant, callerUserId, form.getComments());
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long callerUserId, Long requestId) {
        LeaveRequestPojo req = requestSvc.findByIdOrNull(requestId);
        if (req == null || !callerUserId.equals(req.getUserId())) {
            throw new NotFoundException("leave request not found id=" + requestId);
        }
        if (!CANCELLABLE.contains(req.getStatus())) {
            throw new BadRequestException("this request can no longer be cancelled");
        }
        req.setStatus(LeaveStatus.CANCELLED);
        approvalSvc.notifyCancelled(req, employeeSvc.findByUserId(callerUserId));
    }
}
