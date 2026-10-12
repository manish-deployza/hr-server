package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.exception.NotAuthException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.LeaveApplyData;
import com.leaveapp.model.data.LeaveBalanceData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.LeaveActionForm;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.LeaveFlow;

// Companion to LeaveControllerTest, which covers the object-level view/act rules but
// leaves the thin passthrough endpoints, the ORG_ADMIN arms of those rules, and the
// @PreAuthorize gate untouched.
public class LeaveControllerCoverageTest {

    /* Variables */

    private static final Long REQUEST_ID = 42L;
    private static final Long APPLICANT_ID = 100L;
    private static final Long MANAGER_ID = 200L;

    private final RecordingFlow flow = new RecordingFlow();
    private final StubEmpService empSvc = new StubEmpService();
    private final StubUserService userSvc = new StubUserService();
    private final LeaveController target = new LeaveController();
    private final LeaveController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "leaveFlow", flow);
        ReflectionTestUtils.setField(target, "empSvc", empSvc);
        ReflectionTestUtils.setField(target, "userSvc", userSvc);
        flow.applicantUserId = APPLICANT_ID;
        empSvc.put(APPLICANT_ID, 9L, MANAGER_ID);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ---- passthrough endpoints ----

    @Test
    public void leaveTypes_passesOwnUserId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.types, controller.leaveTypes());
        assertEquals(List.of("leaveTypes:5"), flow.log);
    }

    @Test
    public void myBalances_passesOwnUserId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.balances, controller.myBalances());
        assertEquals(List.of("myBalances:5"), flow.log);
    }

    @Test
    public void apply_passesOwnUserIdAndForm() {
        authenticate(5L, "ROLE_EMPLOYEE");
        LeaveApplyForm form = new LeaveApplyForm();
        form.setLeaveTypeId(3L);

        assertSame(flow.applied, controller.apply(form));
        assertEquals(List.of("apply:5:3"), flow.log);
    }

    @Test
    public void myRequests_recentFlagPassedThrough() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.requests, controller.myRequests(true));
        controller.myRequests(false);
        assertEquals(List.of("myRequests:5:true", "myRequests:5:false"), flow.log);
    }

    @Test
    public void cancel_passesOwnUserIdAndRequestId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertNotNull(controller.cancel(REQUEST_ID));
        assertEquals(List.of("cancel:5:42"), flow.log);
    }

    // ---- ORG_ADMIN arms of the object-level rules ----

    @Test
    public void one_sameOrgOrgAdmin_allowed() {
        Long adminId = 300L;
        userSvc.put(adminId, 9L, UserRole.ORG_ADMIN);
        authenticate(adminId, 9L, "ROLE_ORG_ADMIN");
        assertNotNull(controller.one(REQUEST_ID));
    }

    @Test
    public void action_hrStep_bySameOrgOrgAdmin_allowed() {
        flow.pendingRole = ApproverRole.HR;
        Long adminId = 300L;
        userSvc.put(adminId, 9L, UserRole.ORG_ADMIN);
        authenticate(adminId, 9L, "ROLE_ORG_ADMIN");

        assertNotNull(controller.action(REQUEST_ID, actionForm()));
        assertEquals(List.of("act:300:42"), flow.log);
    }

    @Test
    public void action_hrStep_byCrossOrgOrgAdmin_notFound() {
        flow.pendingRole = ApproverRole.HR;
        Long adminId = 300L;
        userSvc.put(adminId, 999L, UserRole.ORG_ADMIN);
        authenticate(adminId, 999L, "ROLE_ORG_ADMIN");
        assertThrows(NotFoundException.class, () -> controller.action(REQUEST_ID, actionForm()));
        assertTrue(flow.log.isEmpty());
    }

    // ---- gate ----

    @Test
    public void allowedRoles_pass() {
        for (UserRole role : List.of(UserRole.ORG_ADMIN, UserRole.HR, UserRole.EMPLOYEE)) {
            authenticate(5L, 9L, "ROLE_" + role.name());
            controller.leaveTypes();
        }
        assertEquals(3, flow.log.size());
    }

    @Test
    public void onboardingAndUnknownRoles_forbidden() {
        for (String authority : List.of("ROLE_ONBOARDING", "ROLE_SOMETHING_ELSE")) {
            authenticate(5L, authority);
            assertThrows(AccessDeniedException.class, controller::leaveTypes);
            assertThrows(AccessDeniedException.class, controller::myBalances);
            assertThrows(AccessDeniedException.class, () -> controller.apply(new LeaveApplyForm()));
            assertThrows(AccessDeniedException.class, () -> controller.myRequests(false));
            assertThrows(AccessDeniedException.class, () -> controller.one(REQUEST_ID));
            assertThrows(AccessDeniedException.class, () -> controller.action(REQUEST_ID, actionForm()));
            assertThrows(AccessDeniedException.class, () -> controller.cancel(REQUEST_ID));
        }
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void principalWithoutUserId_notAuthenticated() {
        authenticate(null, "ROLE_EMPLOYEE");
        assertThrows(NotAuthException.class, controller::leaveTypes);
        assertThrows(NotAuthException.class, controller::myBalances);
        assertThrows(NotAuthException.class, () -> controller.apply(new LeaveApplyForm()));
        assertThrows(NotAuthException.class, () -> controller.myRequests(false));
        assertThrows(NotAuthException.class, () -> controller.one(REQUEST_ID));
        assertThrows(NotAuthException.class, () -> controller.action(REQUEST_ID, actionForm()));
        assertThrows(NotAuthException.class, () -> controller.cancel(REQUEST_ID));
        assertTrue(flow.log.isEmpty());
    }

    /* Private Helper Methods */

    private static LeaveActionForm actionForm() {
        LeaveActionForm form = new LeaveActionForm();
        form.setAction(ApprovalAction.APPROVED);
        return form;
    }

    private static void authenticate(Long userId, String authority) {
        authenticate(userId, 1L, authority);
    }

    private static void authenticate(Long userId, Long orgId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(orgId);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
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

    private static final class StubEmpService extends EmpService {
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

    private static final class RecordingFlow extends LeaveFlow {
        private final List<String> log = new ArrayList<>();
        private final List<LeaveTypeData> types = List.of(new LeaveTypeData());
        private final List<LeaveBalanceData> balances = List.of(new LeaveBalanceData());
        private final List<LeaveRequestData> requests = List.of(new LeaveRequestData());
        private final LeaveApplyData applied = new LeaveApplyData();
        private Long applicantUserId;
        private ApproverRole pendingRole;

        @Override
        public List<LeaveTypeData> leaveTypes(Long userId) {
            log.add("leaveTypes:" + userId);
            return types;
        }

        @Override
        public List<LeaveBalanceData> myBalances(Long userId) {
            log.add("myBalances:" + userId);
            return balances;
        }

        @Override
        public LeaveApplyData apply(Long userId, LeaveApplyForm form) {
            log.add("apply:" + userId + ":" + form.getLeaveTypeId());
            return applied;
        }

        @Override
        public List<LeaveRequestData> myRequests(Long userId, boolean recent) {
            log.add("myRequests:" + userId + ":" + recent);
            return requests;
        }

        @Override
        public LeaveRequestData getRequest(Long requestId) {
            return new LeaveRequestData();
        }

        @Override
        public ApproverRole pendingApproverRole(Long requestId) {
            return pendingRole;
        }

        @Override
        public Long applicantUserId(Long requestId) {
            return applicantUserId;
        }

        @Override
        public void act(Long callerUserId, Long requestId, LeaveActionForm form) {
            log.add("act:" + callerUserId + ":" + requestId);
        }

        @Override
        public void cancel(Long callerUserId, Long requestId) {
            log.add("cancel:" + callerUserId + ":" + requestId);
        }
    }
}
