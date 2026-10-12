package com.leaveapp.service;

// import flow (layering: Service -> Service/Repo -> DB):
// LeaveApprovalService -> LeaveRequestService -> LeaveRequest*Repo -> DB (the approval decision)
// LeaveApprovalService -> LeaveBalanceService -> LeaveLedgerEntryRepo -> DB (consumption debit)
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.EntityType;
import com.leaveapp.model.enums.NotificationType;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.UserPojo;

// The leave-request approval decision and status state machine: assign the single
// approver a type's config points at, apply-time business-rule validation,
// auto-approval when that approver is unreachable, and the ledger debit that follows
// approval. Split out of LeaveFlow to keep it under the class-size limit -- LeaveFlow
// still owns request-shape validation (required fields) and orchestration; this owns
// the leave-type-driven rules. One approval row per request (code review: the old
// multi-step manager-then-HR chain was unused complexity -- one approver is enough).
@Service
public class LeaveApprovalService {

    /* Variables */

    private static final Set<LeaveStatus> TERMINAL =
        Set.of(LeaveStatus.REJECTED, LeaveStatus.CANCELLED, LeaveStatus.LAPSED);

    @Autowired
    private LeaveRequestService requestSvc;

    @Autowired
    private LeaveBalanceService balanceSvc;

    @Autowired
    private NotificationService notifSvc;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    // The notice-days rule, driven by the leave type's configuration, not the
    // request's shape.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public void validateApplication(LeaveTypePojo type, LocalDate startDate) {
        if (startDate.isBefore(LocalDate.now())) {
            throw new BadRequestException("startDate cannot be in the past");
        }
        if (type.getNoticeDays() != null
            && ChronoUnit.DAYS.between(LocalDate.now(), startDate) < type.getNoticeDays()) {
            throw new BadRequestException(
                "this leave type needs " + type.getNoticeDays() + " days advance notice");
        }
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public void requireNoOverlap(Long userId, LocalDate start, LocalDate end) {
        for (LeaveRequestPojo existing : requestSvc.findByUser(userId)) {
            if (TERMINAL.contains(existing.getStatus())) {
                continue;
            }
            if (!existing.getStartDate().isAfter(end) && !existing.getEndDate().isBefore(start)) {
                throw new ConflictException("you already have a leave request that overlaps these dates");
            }
        }
    }

    // Creates the request's one approval row and notifies the approver.
    // type.getApproverRole() decides who (MANAGER = the applicant's manager, the
    // default every type is created with).
    @Transactional(rollbackFor = Exception.class)
    public void assignApprover(LeaveRequestPojo req, LeaveTypePojo type, EmpPojo applicant) {
        ApproverRole role = type.getApproverRole();
        LeaveRequestApprovalPojo approval = new LeaveRequestApprovalPojo();
        approval.setLeaveRequestId(req.getId());
        approval.setApproverRole(role);
        approval.setAction(ApprovalAction.PENDING);
        approval.setCreatedAt(Instant.now());
        requestSvc.saveApproval(approval);
        req.setStatus(pendingStatusFor(role));
        notifyApprover(req, applicant, role, NotificationType.LEAVE_APPLIED, "New leave request",
            displayName(applicant) + " applied for leave from " + req.getStartDate() + " to " + req.getEndDate() + ".");
    }

    // A MANAGER-routed request for an employee with no manager assigned would
    // deadlock -- auto-approve those and write the ledger debit. ponytail: assign a
    // manager and this whole branch is dead code; revisit only if a skip-level
    // approver is ever a real rule.
    @Transactional(rollbackFor = Exception.class)
    public void settleUnreachableApprover(LeaveRequestPojo req, EmpPojo applicant, Long actorId) {
        if (LeaveStatus.PENDING_MANAGER == req.getStatus() && applicant.getManagerId() == null) {
            approve(req, applicant, actorId, "auto-approved: no manager assigned");
        }
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public LeaveRequestApprovalPojo currentStep(LeaveRequestPojo req) {
        return requestSvc.approvalsOf(req.getId()).stream()
            .findFirst()
            .orElseThrow(() -> new BadRequestException("no approval found for this request"));
    }

    // The one approval decision resolves the request directly: stamps it APPROVED,
    // debits the ledger, and notifies the applicant -- no further step to advance to.
    @Transactional(rollbackFor = Exception.class)
    public void approve(LeaveRequestPojo req, EmpPojo applicant, Long actorId, String comments) {
        stampStep(currentStep(req), ApprovalAction.APPROVED, actorId, comments);
        req.setStatus(LeaveStatus.APPROVED);
        balanceSvc.recordConsumption(req.getUserId(), req.getLeaveTypeId(), req.getStartDate(),
            req.getNumDays(), req.getId(), actorId);
        notifSvc.notify(req.getUserId(), NotificationType.LEAVE_APPROVED, "Leave request approved",
            "Your leave request from " + req.getStartDate() + " to " + req.getEndDate() + " has been approved.",
            EntityType.LEAVE_REQUEST, req.getId());
    }

    // Fires LEAVE_CANCELLED: the manager always, or HR/admin instead if this
    // request's one approval was routed to HR.
    @Transactional(rollbackFor = Exception.class)
    public void notifyCancelled(LeaveRequestPojo req, EmpPojo applicant) {
        String message = displayName(applicant) + "'s leave request from " + req.getStartDate() + " to "
            + req.getEndDate() + " was cancelled.";
        if (ApproverRole.HR == currentStep(req).getApproverRole()) {
            notifyHr(applicant.getOrgId(), NotificationType.LEAVE_CANCELLED, "Leave request cancelled",
                message, req.getId());
            return;
        }
        if (applicant.getManagerId() != null) {
            notifSvc.notify(applicant.getManagerId(), NotificationType.LEAVE_CANCELLED, "Leave request cancelled",
                message, EntityType.LEAVE_REQUEST, req.getId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void stampStep(LeaveRequestApprovalPojo step, ApprovalAction action, Long actorId, String comments) {
        step.setAction(action);
        step.setApproverUserId(actorId);
        step.setComments(comments);
        step.setActedAt(Instant.now());
    }

    /* Private Helper Methods */

    private static LeaveStatus pendingStatusFor(ApproverRole role) {
        return ApproverRole.HR == role ? LeaveStatus.PENDING_HR : LeaveStatus.PENDING_MANAGER;
    }

    // MANAGER role -> the applicant's manager (silent no-op if none, same as the
    // auto-approve branch above); HR role -> every HR/ORG_ADMIN in the org.
    private void notifyApprover(LeaveRequestPojo req, EmpPojo applicant, ApproverRole role, NotificationType type,
        String title, String message) {
        if (ApproverRole.MANAGER == role) {
            if (applicant.getManagerId() != null) {
                notifSvc.notify(applicant.getManagerId(), type, title, message, EntityType.LEAVE_REQUEST, req.getId());
            }
            return;
        }
        notifyHr(applicant.getOrgId(), type, title, message, req.getId());
    }

    private void notifyHr(Long orgId, NotificationType type, String title, String message, Long reqId) {
        for (UserPojo hr : userSvc.findHrAndAdminUsersByOrg(orgId)) {
            notifSvc.notify(hr.getId(), type, title, message, EntityType.LEAVE_REQUEST, reqId);
        }
    }

    private static String displayName(EmpPojo emp) {
        String name = emp.getName();
        return name == null || name.isBlank() ? "An employee" : name;
    }
}
