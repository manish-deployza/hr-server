package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.leaveapp.model.data.HolidayData;
import com.leaveapp.model.data.IdData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.HolidayForm;
import com.leaveapp.model.form.LeaveTypeForm;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.HrLeaveFlow;

// Plain unit test, same style as AdminUserControllerTest: ORG_ADMIN and HR get in, every
// other authority is rejected, and the caller's org from the token (never the request) is
// what reaches the Flow, together with the path id and body untouched.
public class HrLeaveControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final HrLeaveController target = new HrLeaveController();
    private final HrLeaveController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "hrLeaveFlow", flow);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void leaveRequests_passesOrgIdAndReturnsFlowResult() {
        authenticate(9L, "ROLE_HR");
        assertSame(flow.requests, controller.leaveRequests());
        assertEquals(List.of("allRequests:9"), flow.log);
    }

    @Test
    public void leaveTypes_passesOrgId() {
        authenticate(9L, "ROLE_HR");
        assertSame(flow.types, controller.leaveTypes());
        assertEquals(List.of("leaveTypes:9"), flow.log);
    }

    @Test
    public void createLeaveType_passesOrgIdAndForm() {
        authenticate(9L, "ROLE_ORG_ADMIN");
        LeaveTypeForm form = new LeaveTypeForm();
        form.setName("Sick");

        assertSame(flow.id, controller.createLeaveType(form));
        assertEquals(List.of("createLeaveType:9:Sick"), flow.log);
    }

    @Test
    public void updateLeaveType_passesOrgIdPathIdAndForm() {
        authenticate(9L, "ROLE_HR");
        LeaveTypeForm form = new LeaveTypeForm();
        form.setName("Casual");

        assertNotNull(controller.updateLeaveType(4L, form));
        assertEquals(List.of("updateLeaveType:9:4:Casual"), flow.log);
    }

    @Test
    public void deleteLeaveType_passesOrgIdAndPathId() {
        authenticate(9L, "ROLE_HR");
        assertNotNull(controller.deleteLeaveType(4L));
        assertEquals(List.of("deleteLeaveType:9:4"), flow.log);
    }

    @Test
    public void holidays_yearGiven_passedThrough() {
        authenticate(9L, "ROLE_HR");
        assertSame(flow.holidays, controller.holidays(2026));
        assertEquals(List.of("holidays:9:2026"), flow.log);
    }

    @Test
    public void holidays_yearOmitted_passedAsNull() {
        authenticate(9L, "ROLE_HR");
        controller.holidays(null);
        assertEquals(List.of("holidays:9:null"), flow.log);
    }

    @Test
    public void createHoliday_passesOrgIdAndForm() {
        authenticate(9L, "ROLE_HR");
        HolidayForm form = new HolidayForm();
        form.setName("Diwali");

        assertSame(flow.id, controller.createHoliday(form));
        assertEquals(List.of("createHoliday:9:Diwali"), flow.log);
    }

    @Test
    public void updateHoliday_passesOrgIdPathIdAndForm() {
        authenticate(9L, "ROLE_HR");
        HolidayForm form = new HolidayForm();
        form.setName("Holi");

        assertNotNull(controller.updateHoliday(6L, form));
        assertEquals(List.of("updateHoliday:9:6:Holi"), flow.log);
    }

    @Test
    public void deleteHoliday_passesOrgIdAndPathId() {
        authenticate(9L, "ROLE_HR");
        assertNotNull(controller.deleteHoliday(6L));
        assertEquals(List.of("deleteHoliday:9:6"), flow.log);
    }

    @Test
    public void everyEndpoint_orgAdminAndHrAllowed() {
        for (String authority : List.of("ROLE_ORG_ADMIN", "ROLE_HR")) {
            authenticate(9L, authority);
            callEveryEndpoint();
        }
        assertEquals(18, flow.log.size());
    }

    @Test
    public void everyEndpoint_otherAuthorities_forbidden() {
        for (String authority : List.of("ROLE_EMPLOYEE", "ROLE_ONBOARDING", "ROLE_SOMETHING_ELSE")) {
            authenticate(9L, authority);
            assertThrows(AccessDeniedException.class, this::callEveryEndpoint);
            assertThrows(AccessDeniedException.class, controller::leaveTypes);
            assertThrows(AccessDeniedException.class, () -> controller.deleteHoliday(1L));
        }
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void everyEndpoint_anonymous_rejected() {
        assertThrows(Exception.class, controller::leaveRequests);
        assertThrows(Exception.class, () -> controller.deleteLeaveType(1L));
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void principalWithoutOrgId_notAuthenticated() {
        authenticate(null, "ROLE_HR");
        assertThrows(NotAuthException.class, controller::leaveRequests);
        assertThrows(NotAuthException.class, controller::leaveTypes);
        assertThrows(NotAuthException.class, () -> controller.createLeaveType(new LeaveTypeForm()));
        assertThrows(NotAuthException.class, () -> controller.updateLeaveType(1L, new LeaveTypeForm()));
        assertThrows(NotAuthException.class, () -> controller.deleteLeaveType(1L));
        assertThrows(NotAuthException.class, () -> controller.holidays(null));
        assertThrows(NotAuthException.class, () -> controller.createHoliday(new HolidayForm()));
        assertThrows(NotAuthException.class, () -> controller.updateHoliday(1L, new HolidayForm()));
        assertThrows(NotAuthException.class, () -> controller.deleteHoliday(1L));
        assertTrue(flow.log.isEmpty());
    }

    /* Private Helper Methods */

    private void callEveryEndpoint() {
        controller.leaveRequests();
        controller.leaveTypes();
        controller.createLeaveType(new LeaveTypeForm());
        controller.updateLeaveType(1L, new LeaveTypeForm());
        controller.deleteLeaveType(1L);
        controller.holidays(null);
        controller.createHoliday(new HolidayForm());
        controller.updateHoliday(1L, new HolidayForm());
        controller.deleteHoliday(1L);
    }

    private static void authenticate(Long orgId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(orgId);
        p.setRoles(List.of(UserRole.HR));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class RecordingFlow extends HrLeaveFlow {
        private final List<String> log = new ArrayList<>();
        private final List<LeaveRequestData> requests = List.of(new LeaveRequestData());
        private final List<LeaveTypeData> types = List.of(new LeaveTypeData());
        private final List<HolidayData> holidays = List.of(new HolidayData());
        private final IdData id = new IdData();

        @Override
        public List<LeaveRequestData> allRequests(Long orgId) {
            log.add("allRequests:" + orgId);
            return requests;
        }

        @Override
        public List<LeaveTypeData> leaveTypes(Long orgId) {
            log.add("leaveTypes:" + orgId);
            return types;
        }

        @Override
        public IdData createLeaveType(Long orgId, LeaveTypeForm form) {
            log.add("createLeaveType:" + orgId + ":" + form.getName());
            return id;
        }

        @Override
        public void updateLeaveType(Long orgId, Long id, LeaveTypeForm form) {
            log.add("updateLeaveType:" + orgId + ":" + id + ":" + form.getName());
        }

        @Override
        public void deleteLeaveType(Long orgId, Long id) {
            log.add("deleteLeaveType:" + orgId + ":" + id);
        }

        @Override
        public List<HolidayData> holidays(Long orgId, Integer year) {
            log.add("holidays:" + orgId + ":" + year);
            return holidays;
        }

        @Override
        public IdData createHoliday(Long orgId, HolidayForm form) {
            log.add("createHoliday:" + orgId + ":" + form.getName());
            return id;
        }

        @Override
        public void updateHoliday(Long orgId, Long id, HolidayForm form) {
            log.add("updateHoliday:" + orgId + ":" + id + ":" + form.getName());
        }

        @Override
        public void deleteHoliday(Long orgId, Long id) {
            log.add("deleteHoliday:" + orgId + ":" + id);
        }
    }
}
