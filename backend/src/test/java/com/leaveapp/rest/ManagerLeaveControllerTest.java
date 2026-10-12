package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.NotAuthException;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.ManagerTeamRowData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.ManagerLeaveFlow;

// Plain unit test, same style as AdminUserControllerTest: any of the three real roles may
// ask (scope is the Flow's job), onboarding / unknown / anonymous are rejected, and the
// caller's own id from the token is what reaches the Flow.
public class ManagerLeaveControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final ManagerLeaveController target = new ManagerLeaveController();
    private final ManagerLeaveController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "mgrLeaveFlow", flow);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void team_passesOwnUserId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.team, controller.team());
        assertEquals(List.of("team:5"), flow.log);
    }

    @Test
    public void leaveRequests_passesOwnUserId() {
        authenticate(5L, "ROLE_EMPLOYEE");
        assertSame(flow.requests, controller.leaveRequests());
        assertEquals(List.of("teamRequests:5"), flow.log);
    }

    @Test
    public void allowedRoles_pass() {
        for (UserRole role : List.of(UserRole.ORG_ADMIN, UserRole.HR, UserRole.EMPLOYEE)) {
            authenticate(5L, "ROLE_" + role.name());
            controller.team();
            controller.leaveRequests();
        }
        assertEquals(6, flow.log.size());
    }

    @Test
    public void onboardingAndUnknownRoles_forbidden() {
        for (String authority : List.of("ROLE_ONBOARDING", "ROLE_SOMETHING_ELSE")) {
            authenticate(5L, authority);
            assertThrows(AccessDeniedException.class, controller::team);
            assertThrows(AccessDeniedException.class, controller::leaveRequests);
        }
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void anonymous_rejected() {
        assertThrows(Exception.class, controller::team);
        assertThrows(Exception.class, controller::leaveRequests);
    }

    @Test
    public void principalWithoutUserId_notAuthenticated() {
        authenticate(null, "ROLE_EMPLOYEE");
        assertThrows(NotAuthException.class, controller::team);
        assertThrows(NotAuthException.class, controller::leaveRequests);
        assertTrue(flow.log.isEmpty());
    }

    /* Private Helper Methods */

    private static void authenticate(Long userId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(1L);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class RecordingFlow extends ManagerLeaveFlow {
        private final List<String> log = new ArrayList<>();
        private final List<ManagerTeamRowData> team = List.of(new ManagerTeamRowData());
        private final List<LeaveRequestData> requests = List.of(new LeaveRequestData());

        @Override
        public List<ManagerTeamRowData> team(Long managerUserId) {
            log.add("team:" + managerUserId);
            return team;
        }

        @Override
        public List<LeaveRequestData> teamRequests(Long managerUserId) {
            log.add("teamRequests:" + managerUserId);
            return requests;
        }
    }
}
