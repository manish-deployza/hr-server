package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.ManagerTeamRowData;
import com.leaveapp.workflow.ManagerLeaveFlow;

// Manager leave views. There is no MANAGER role (see UserRole) -- "manager" is
// derived from employee.manager_id, so any authenticated role may call this; scope
// ("only my direct reports") is enforced in the Flow. Approve/reject itself goes
// through POST /api/leave-requests/{id}/action.
@RestController
@RequestMapping("/api/manager")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR', 'EMPLOYEE')")
public class ManagerLeaveController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(ManagerLeaveController.class);

    @Autowired
    private ManagerLeaveFlow mgrLeaveFlow;

    /* Public Methods */

    @GetMapping("/team")
    public List<ManagerTeamRowData> team() {
        Long userId = requireUserId();
        log.info("GET /api/manager/team userId={}", userId);
        return mgrLeaveFlow.team(userId);
    }

    @GetMapping("/leave-requests")
    public List<LeaveRequestData> leaveRequests() {
        Long userId = requireUserId();
        log.info("GET /api/manager/leave-requests userId={}", userId);
        return mgrLeaveFlow.teamRequests(userId);
    }
}
