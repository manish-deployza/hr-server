package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.HrEmpFlow;

// Plain unit test, same style as HrEmpControllerTest: the ORG_ADMIN-only gate, that the
// principal's orgId (not the request) reaches the Flow, and the cross-org 404 on resend.
public class AdminUserControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final AdminUserController target = new AdminUserController();
    private final AdminUserController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "hrEmpFlow", flow);
        ReflectionTestUtils.setField(target, "userSvc", new StubUserService());
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void invite_asOrgAdmin_passesPrincipalsOrgIdAndFormToFlow() {
        authenticate(9L, UserRole.ORG_ADMIN);
        UserInviteForm form = new UserInviteForm();

        assertEquals(555L, controller.invite(form).getId());
        assertEquals(9L, flow.orgId);
        assertSame(form, flow.form);
    }

    @Test
    public void invite_asHr_forbidden() {
        authenticate(9L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.invite(new UserInviteForm()));
    }

    @Test
    public void resendInvite_sameOrg_reachesFlow() {
        authenticate(9L, UserRole.ORG_ADMIN);
        controller.resendInvite(77L);
        assertEquals(77L, flow.resendTargetId);
    }

    @Test
    public void resendInvite_crossOrgTarget_notFound() {
        authenticate(8L, UserRole.ORG_ADMIN);
        assertThrows(NotFoundException.class, () -> controller.resendInvite(77L));
    }

    @Test
    public void resendInvite_asHr_forbidden() {
        authenticate(9L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.resendInvite(77L));
    }

    /* Private Helper Methods */

    private static void authenticate(Long orgId, UserRole role) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(orgId);
        p.setRoles(List.of(role));
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, authorities));
    }

    /* Test Doubles */

    // Hand-written stubs -- Mockito isn't a dependency of this project.
    private static final class StubUserService extends UserService {
        @Override
        public UserPojo findById(Long id) {
            UserPojo u = new UserPojo();
            u.setId(id);
            u.setOrgId(9L);
            return u;
        }
    }

    private static final class RecordingFlow extends HrEmpFlow {
        private Long orgId;
        private UserInviteForm form;
        private Long resendTargetId;

        @Override
        public Long invite(Long orgId, UserInviteForm form) {
            this.orgId = orgId;
            this.form = form;
            return 555L;
        }

        @Override
        public void resendInvite(Long targetId) {
            this.resendTargetId = targetId;
        }
    }
}
