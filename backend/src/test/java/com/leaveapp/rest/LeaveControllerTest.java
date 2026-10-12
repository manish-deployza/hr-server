package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.form.LeaveActionForm;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.LeaveFlow;

// Plain unit test -- no Spring/web context. Covers what the controller adds over
// LeaveFlow: who may view a request (owner, manager, same-org HR/ORG admin -- 404
// otherwise) and who may act on one (the pending step's manager, or same-org HR/ORG
// admin for an HR step -- this is where act_byNonManager_isForbidden moved
// after authorization came out of LeaveFlow).
public class LeaveControllerTest {

    /* Variables */

    private static final Long REQUEST_ID = 42L;
    private static final Long APPLICANT_ID = 100L;
    private static final Long MANAGER_ID = 200L;

    private final StubLeaveFlow flow = new StubLeaveFlow();
    private final StubEmployeeService empSvc = new StubEmployeeService();
    private final StubUserService userSvc = new StubUserService();
    private final LeaveController controller = new LeaveController();

    {
        ReflectionTestUtils.setField(controller, "leaveFlow", flow);
        ReflectionTestUtils.setField(controller, "empSvc", empSvc);
        ReflectionTestUtils.setField(controller, "userSvc", userSvc);
        flow.applicantUserId = APPLICANT_ID;
        empSvc.put(APPLICANT_ID, 9L, MANAGER_ID);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void one_owner_isAllowed() {
        authenticate(APPLICANT_ID, 9L, UserRole.EMPLOYEE);
        assertNotNull(controller.one(REQUEST_ID));
    }

    @Test
    public void one_theirManager_isAllowed() {
        authenticate(MANAGER_ID, 9L, UserRole.EMPLOYEE);
        assertNotNull(controller.one(REQUEST_ID));
    }

    @Test
    public void one_sameOrgHr_isAllowed() {
        Long hrId = 300L;
        userSvc.put(hrId, 9L, UserRole.HR);
        authenticate(hrId, 9L, UserRole.HR);
        assertNotNull(controller.one(REQUEST_ID));
    }

    @Test
    public void one_unrelatedSameOrgEmployee_notFound() {
        Long bystanderId = 400L;
        userSvc.put(bystanderId, 9L, UserRole.EMPLOYEE);
        authenticate(bystanderId, 9L, UserRole.EMPLOYEE);
        assertThrows(NotFoundException.class, () -> controller.one(REQUEST_ID));
    }

    @Test
    public void one_crossOrgHr_notFound() {
        Long hrId = 300L;
        userSvc.put(hrId, 999L, UserRole.HR);
        authenticate(hrId, 999L, UserRole.HR);
        assertThrows(NotFoundException.class, () -> controller.one(REQUEST_ID));
    }

    @Test
    public void action_managerStep_byTheirManager_isAllowed() {
        flow.pendingRole = ApproverRole.MANAGER;
        authenticate(MANAGER_ID, 9L, UserRole.EMPLOYEE);
        assertNotNull(controller.action(REQUEST_ID, action("APPROVED")));
        assertEquals(REQUEST_ID, flow.actedRequestId);
    }

    // Moved from LeaveFlowTest.act_byNonManager_isForbidden -- who-may-act is now
    // authorized here, not in LeaveFlow.
    @Test
    public void action_managerStep_byBystander_forbidden() {
        flow.pendingRole = ApproverRole.MANAGER;
        Long bystanderId = 400L;
        userSvc.put(bystanderId, 9L, UserRole.EMPLOYEE);
        authenticate(bystanderId, 9L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, () -> controller.action(REQUEST_ID, action("APPROVED")));
    }

    @Test
    public void action_hrStep_bySameOrgHr_isAllowed() {
        flow.pendingRole = ApproverRole.HR;
        Long hrId = 300L;
        userSvc.put(hrId, 9L, UserRole.HR);
        authenticate(hrId, 9L, UserRole.HR);
        assertNotNull(controller.action(REQUEST_ID, action("APPROVED")));
    }

    @Test
    public void action_hrStep_bySameOrgEmployee_forbidden() {
        flow.pendingRole = ApproverRole.HR;
        Long bystanderId = 400L;
        userSvc.put(bystanderId, 9L, UserRole.EMPLOYEE);
        authenticate(bystanderId, 9L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, () -> controller.action(REQUEST_ID, action("APPROVED")));
    }

    @Test
    public void action_hrStep_byCrossOrgHr_notFound() {
        flow.pendingRole = ApproverRole.HR;
        Long hrId = 300L;
        userSvc.put(hrId, 999L, UserRole.HR);
        authenticate(hrId, 999L, UserRole.HR);
        assertThrows(NotFoundException.class, () -> controller.action(REQUEST_ID, action("APPROVED")));
    }

    /* Private Helper Methods */

    private static LeaveActionForm action(String action) {
        LeaveActionForm form = new LeaveActionForm();
        form.setAction(ApprovalAction.valueOf(action));
        return form;
    }

    private static void authenticate(Long userId, Long orgId, UserRole role) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(orgId);
        p.setRoles(List.of(role));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    /* Test Doubles */

    private static final class StubUserService extends UserService {
        private final Map<Long, UserPojo> byId = new HashMap<>();

        void put(Long id, Long orgId, UserRole role) {
            UserPojo u = new UserPojo();
            u.setId(id);
            u.setOrgId(orgId);
            u.setRole(role);
            byId.put(id, u);
        }

        @Override
        public UserPojo findById(Long id) {
            UserPojo u = byId.get(id);
            if (u == null) {
                throw new NotFoundException("user not found id=" + id);
            }
            return u;
        }
    }

    private static final class StubEmployeeService extends EmpService {
        private final Map<Long, EmpPojo> byUserId = new HashMap<>();

        void put(Long userId, Long orgId, Long managerId) {
            EmpPojo e = new EmpPojo();
            e.setStatus(EmpStatus.ACTIVE);
            e.setUserId(userId);
            e.setOrgId(orgId);
            e.setManagerId(managerId);
            byUserId.put(userId, e);
        }

        @Override
        public EmpPojo findByUserId(Long userId) {
            EmpPojo e = byUserId.get(userId);
            if (e == null) {
                throw new NotFoundException("employee profile not found userId=" + userId);
            }
            return e;
        }
    }

    private static final class StubLeaveFlow extends LeaveFlow {
        private Long applicantUserId;
        private ApproverRole pendingRole;
        private Long actedRequestId;

        @Override
        public Long applicantUserId(Long requestId) {
            return applicantUserId;
        }

        @Override
        public ApproverRole pendingApproverRole(Long requestId) {
            return pendingRole;
        }

        @Override
        public LeaveRequestData getRequest(Long requestId) {
            return new LeaveRequestData();
        }

        @Override
        public void act(Long callerUserId, Long requestId, LeaveActionForm form) {
            this.actedRequestId = requestId;
        }
    }
}
