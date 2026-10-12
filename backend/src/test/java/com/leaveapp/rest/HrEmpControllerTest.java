package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.HrEmpFlow;

// Plain unit test -- no Spring/web context. Covers what the controller adds over the
// Flow: the ORG_ADMIN/HR gate, the two body-dependent role rules, the
// per-target object-level check (cross-org 404, HR-vs-non-EMPLOYEE-target 403),
// and that the principal's orgId (not anything in the request) is what reaches the Flow.
public class HrEmpControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final StubUserService userSvc = new StubUserService();
    private final HrEmpController target = new HrEmpController();
    private final HrEmpController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "hrEmpFlow", flow);
        ReflectionTestUtils.setField(target, "userSvc", userSvc);
        userSvc.put(77L, 9L, UserRole.EMPLOYEE); // default target: same org as authenticate(9L, ...), EMPLOYEE role
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void list_asHr_returnsFlowListForPrincipalsOrg() {
        authenticate(9L, UserRole.HR);

        assertSame(flow.listReturns, controller.list());
        assertEquals(9L, flow.listOrgId);
    }

    @Test
    public void list_wrongRole_forbidden() {
        authenticate(9L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, controller::list);
    }

    @Test
    public void list_noPrincipal_denied() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, controller::list);
    }

    @Test
    public void update_asHr_passesOrgIdTargetIdAndFormToFlow() {
        authenticate(9L, UserRole.HR);
        EmpForm form = new EmpForm();

        assertSame(flow.updateReturns, controller.update(77L, form));
        assertEquals(9L, flow.updateOrgId);
        assertEquals(77L, flow.updateTargetId);
        assertSame(form, flow.updateForm);
    }

    @Test
    public void update_asOrgAdmin_canTargetAnyRole() {
        authenticate(9L, UserRole.ORG_ADMIN);
        userSvc.put(77L, 9L, UserRole.HR);
        assertSame(flow.updateReturns, controller.update(77L, new EmpForm()));
    }

    @Test
    public void update_hrTargetingHrRow_forbidden() {
        authenticate(9L, UserRole.HR);
        userSvc.put(77L, 9L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.update(77L, new EmpForm()));
    }

    @Test
    public void update_crossOrgTarget_notFound() {
        authenticate(9L, UserRole.HR);
        userSvc.put(77L, 999L, UserRole.EMPLOYEE);
        assertThrows(NotFoundException.class, () -> controller.update(77L, new EmpForm()));
    }

    @Test
    public void update_unknownTarget_notFound() {
        authenticate(9L, UserRole.HR);
        assertThrows(NotFoundException.class, () -> controller.update(999999L, new EmpForm()));
    }

    @Test
    public void update_wrongRole_forbidden() {
        authenticate(9L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, () -> controller.update(77L, new EmpForm()));
    }

    @Test
    public void update_orgAdminInPayload_badRequest() {
        authenticate(9L, UserRole.ORG_ADMIN);
        assertThrows(BadRequestException.class, () -> controller.update(77L, formWithRole(UserRole.ORG_ADMIN)));
    }

    @Test
    public void update_hrGrantingHr_forbidden() {
        authenticate(9L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.update(77L, formWithRole(UserRole.HR)));
    }

    @Test
    public void deactivate_asHr_passesTargetIdToFlow() {
        authenticate(9L, UserRole.HR);

        assertNotNull(controller.deactivate(77L));
        assertEquals(77L, flow.deactivateTargetId);
    }

    @Test
    public void deactivate_asOrgAdmin_canTargetAnyRole() {
        authenticate(9L, UserRole.ORG_ADMIN);
        userSvc.put(77L, 9L, UserRole.HR);
        controller.deactivate(77L);
        assertEquals(77L, flow.deactivateTargetId);
    }

    @Test
    public void deactivate_hrTargetingHrRow_forbidden() {
        authenticate(9L, UserRole.HR);
        userSvc.put(77L, 9L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.deactivate(77L));
    }

    @Test
    public void deactivate_crossOrgTarget_notFound() {
        authenticate(9L, UserRole.HR);
        userSvc.put(77L, 999L, UserRole.EMPLOYEE);
        assertThrows(NotFoundException.class, () -> controller.deactivate(77L));
    }

    @Test
    public void deactivate_wrongRole_forbidden() {
        authenticate(9L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, () -> controller.deactivate(77L));
    }

    @Test
    public void deactivate_ownAccount_badRequest() {
        authenticate(9L, UserRole.ORG_ADMIN); // authenticate() sets principal userId = 1
        assertThrows(BadRequestException.class, () -> controller.deactivate(1L));
    }

    /* Private Helper Methods */

    private static EmpForm formWithRole(UserRole role) {
        EmpForm f = new EmpForm();
        f.setEmail("new@acme.test");
        f.setRole(role);
        return f;
    }

    private static void authenticate(Long orgId, UserRole role) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(orgId);
        p.setRoles(List.of(role));
        // "ROLE_" + name(), matching JwtAuthFilter -- @PreAuthorize's hasRole() reads
        // these GrantedAuthority values, not UserPrincipal.getRoles() directly.
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, authorities));
    }

    /* Test Doubles */

    // Hand-written stub -- Mockito isn't a dependency of this project.
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

    private static final class RecordingFlow extends HrEmpFlow {
        private final List<EmpRowData> listReturns = List.of(new EmpRowData());
        private final EmpRowData updateReturns = new EmpRowData();
        private Long listOrgId;
        private Long updateOrgId;
        private Long updateTargetId;
        private EmpForm updateForm;
        private Long deactivateTargetId;

        @Override
        public List<EmpRowData> list(Long orgId) {
            this.listOrgId = orgId;
            return listReturns;
        }

        @Override
        public EmpRowData update(Long orgId, Long targetId, EmpForm form) {
            this.updateOrgId = orgId;
            this.updateTargetId = targetId;
            this.updateForm = form;
            return updateReturns;
        }

        @Override
        public void deactivate(Long targetId) {
            this.deactivateTargetId = targetId;
        }
    }
}
