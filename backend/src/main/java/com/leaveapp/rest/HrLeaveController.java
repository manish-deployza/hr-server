package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.HolidayData;
import com.leaveapp.model.data.IdData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.form.LeaveTypeForm;
import com.leaveapp.pojo.UserRole;
import com.leaveapp.workflow.HrLeaveFlow;

// HR / org-admin leave administration. ORG_ADMIN or HR_ADMIN only; the org is the
// JWT claim, never a URL or body field.
@RestController
@RequestMapping("/api/hr")
public class HrLeaveController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(HrLeaveController.class);

    @Autowired
    private HrLeaveFlow hrLeaveFlow;

    /* Public Methods */

    @GetMapping("/leave-requests")
    public List<LeaveRequestData> leaveRequests() {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        Long orgId = requireOrgId();
        log.info("GET /api/hr/leave-requests orgId={}", orgId);
        return hrLeaveFlow.allRequests(orgId);
    }

    @GetMapping("/leave-types")
    public List<LeaveTypeData> leaveTypes() {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        return hrLeaveFlow.leaveTypes(requireOrgId());
    }

    @PostMapping("/leave-types")
    public IdData createLeaveType(@RequestBody LeaveTypeForm form) {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        log.info("POST /api/hr/leave-types code={}", form.getCode());
        return hrLeaveFlow.createLeaveType(requireOrgId(), form);
    }

    @PatchMapping("/leave-types/{id}")
    public AckData updateLeaveType(@PathVariable("id") Long id, @RequestBody LeaveTypeForm form) {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        log.info("PATCH /api/hr/leave-types/{}", id);
        hrLeaveFlow.updateLeaveType(requireOrgId(), id, form);
        return new AckData();
    }

    @DeleteMapping("/leave-types/{id}")
    public AckData deleteLeaveType(@PathVariable("id") Long id) {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        log.info("DELETE /api/hr/leave-types/{}", id);
        hrLeaveFlow.deleteLeaveType(requireOrgId(), id);
        return new AckData();
    }

    @GetMapping("/holidays")
    public List<HolidayData> holidays() {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        return hrLeaveFlow.holidays(requireOrgId());
    }
}
