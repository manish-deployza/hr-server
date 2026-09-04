package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.LeaveApplyData;
import com.leaveapp.model.data.LeaveBalanceData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.form.LeaveActionForm;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.pojo.ApproverRole;
import com.leaveapp.pojo.EmployeePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.pojo.UserRole;
import com.leaveapp.service.EmployeeService;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.LeaveFlow;

// Employee-facing leave endpoints. Every route needs a logged-in user; GET .../{id}
// and POST .../action additionally need an object-level check -- who may see or act
// on a given request -- which this controller resolves via a couple of read-only
// Flow/Service lookups before calling into the Flow's mutating methods.
@RestController
@RequestMapping("/api")
public class LeaveController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(LeaveController.class);

    @Autowired
    private LeaveFlow leaveFlow;

    @Autowired
    private EmployeeService empSvc;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    @GetMapping("/leave-types")
    public List<LeaveTypeData> leaveTypes() {
        return leaveFlow.leaveTypes(requireUserId());
    }

    @GetMapping("/leave-balances/me")
    public List<LeaveBalanceData> myBalances() {
        return leaveFlow.myBalances(requireUserId());
    }

    @PostMapping("/leave-requests")
    public LeaveApplyData apply(@RequestBody LeaveApplyForm form) {
        Long userId = requireUserId();
        log.info("POST /api/leave-requests userId={} leaveTypeId={}", userId, form.getLeaveTypeId());
        return leaveFlow.apply(userId, form);
    }

    @GetMapping("/leave-requests/me")
    public List<LeaveRequestData> myRequests(
        @RequestParam(name = "recent", required = false, defaultValue = "false") boolean recent) {
        return leaveFlow.myRequests(requireUserId(), recent);
    }

    @GetMapping("/leave-requests/{id}")
    public LeaveRequestData one(@PathVariable("id") Long id) {
        Long userId = requireUserId();
        requireCanView(id, userId);
        return leaveFlow.getRequest(id);
    }

    @PostMapping("/leave-requests/{id}/action")
    public AckData action(@PathVariable("id") Long id, @RequestBody LeaveActionForm form) {
        Long userId = requireUserId();
        requireCanAct(id, userId);
        log.info("POST /api/leave-requests/{}/action userId={} action={}", id, userId, form.getAction());
        leaveFlow.act(userId, id, form);
        return new AckData();
    }

    @PostMapping("/leave-requests/{id}/cancel")
    public AckData cancel(@PathVariable("id") Long id) {
        Long userId = requireUserId();
        log.info("POST /api/leave-requests/{}/cancel userId={}", id, userId);
        leaveFlow.cancel(userId, id);
        return new AckData();
    }

    /* Private Helper Methods */

    // Viewing a request: the owner, this employee's manager, or same-org HR/ORG
    // admin. Anyone else gets 404 (no existence leak), same as before this moved
    // out of LeaveFlow.
    private void requireCanView(Long requestId, Long callerId) {
        Long applicantUserId = leaveFlow.applicantUserId(requestId);
        if (callerId.equals(applicantUserId)) {
            return;
        }
        EmployeePojo applicant = empSvc.findByUserId(applicantUserId);
        if (callerId.equals(applicant.getManagerId())) {
            return;
        }
        UserPojo caller = userSvc.findById(callerId);
        if (caller.getOrganizationId().equals(applicant.getOrganizationId())
            && (UserRole.HR_ADMIN == caller.getRole() || UserRole.ORG_ADMIN == caller.getRole())) {
            return;
        }
        throw new NotFoundException("leave request not found id=" + requestId);
    }

    // Acting on a request: who may act depends on which step is currently pending --
    // the employee's manager for a MANAGER step, same-org HR/ORG admin for an
    // HR_ADMIN step. A cross-org actor gets 404 (no existence leak); same org but
    // wrong role gets 403.
    private void requireCanAct(Long requestId, Long callerId) {
        ApproverRole pendingRole = leaveFlow.pendingApproverRole(requestId);
        EmployeePojo applicant = empSvc.findByUserId(leaveFlow.applicantUserId(requestId));
        if (ApproverRole.MANAGER == pendingRole) {
            if (!callerId.equals(applicant.getManagerId())) {
                throw new AccessDeniedException("only this employee's manager can act on this step");
            }
            return;
        }
        UserPojo caller = userSvc.findById(callerId);
        if (!caller.getOrganizationId().equals(applicant.getOrganizationId())) {
            throw new NotFoundException("leave request not found id=" + requestId);
        }
        if (UserRole.HR_ADMIN != caller.getRole() && UserRole.ORG_ADMIN != caller.getRole()) {
            throw new AccessDeniedException("only HR can act on this step");
        }
    }
}
