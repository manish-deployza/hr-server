package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.HolidayData;
import com.leaveapp.model.data.IdData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.form.HolidayForm;
import com.leaveapp.model.form.LeaveTypeForm;
import com.leaveapp.workflow.HrLeaveFlow;

// HR / org-admin leave administration. The org is the JWT claim, never a URL
// or body field.
@RestController
@RequestMapping("/api/hr")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR')")
public class HrLeaveController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(HrLeaveController.class);

    @Autowired
    private HrLeaveFlow hrLeaveFlow;

    /* Public Methods */

    @GetMapping("/leave-requests")
    public List<LeaveRequestData> leaveRequests() {
        Long orgId = requireOrgId();
        log.info("GET /api/hr/leave-requests orgId={}", orgId);
        return hrLeaveFlow.allRequests(orgId);
    }

    @GetMapping("/leave-types")
    public List<LeaveTypeData> leaveTypes() {
        return hrLeaveFlow.leaveTypes(requireOrgId());
    }

    @PostMapping("/leave-types")
    public IdData createLeaveType(@RequestBody LeaveTypeForm form) {
        log.info("POST /api/hr/leave-types name={}", form.getName());
        return hrLeaveFlow.createLeaveType(requireOrgId(), form);
    }

    @PatchMapping("/leave-types/{id}")
    public AckData updateLeaveType(@PathVariable("id") Long id, @RequestBody LeaveTypeForm form) {
        log.info("PATCH /api/hr/leave-types/{}", id);
        hrLeaveFlow.updateLeaveType(requireOrgId(), id, form);
        return new AckData();
    }

    @DeleteMapping("/leave-types/{id}")
    public AckData deleteLeaveType(@PathVariable("id") Long id) {
        log.info("DELETE /api/hr/leave-types/{}", id);
        hrLeaveFlow.deleteLeaveType(requireOrgId(), id);
        return new AckData();
    }

    @GetMapping("/holidays")
    public List<HolidayData> holidays(@RequestParam(value = "year", required = false) Integer year) {
        return hrLeaveFlow.holidays(requireOrgId(), year);
    }

    @PostMapping("/holidays")
    public IdData createHoliday(@RequestBody HolidayForm form) {
        log.info("POST /api/hr/holidays name={}", form.getName());
        return hrLeaveFlow.createHoliday(requireOrgId(), form);
    }

    @PatchMapping("/holidays/{id}")
    public AckData updateHoliday(@PathVariable("id") Long id, @RequestBody HolidayForm form) {
        log.info("PATCH /api/hr/holidays/{}", id);
        hrLeaveFlow.updateHoliday(requireOrgId(), id, form);
        return new AckData();
    }

    @DeleteMapping("/holidays/{id}")
    public AckData deleteHoliday(@PathVariable("id") Long id) {
        log.info("DELETE /api/hr/holidays/{}", id);
        hrLeaveFlow.deleteHoliday(requireOrgId(), id);
        return new AckData();
    }
}
