package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.data.OrgData;
import com.leaveapp.model.form.OrgForm;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.OrgFlow;

// Plain unit test -- no Spring/web context (TestConfig deliberately excludes
// controllers). Covers what the controller adds over OrgFlow: the ORG_ADMIN gate,
// the "orgId from the JWT principal, not the request" rule, and form pass-through.
// controller is target wrapped in a hand-built @PreAuthorize-enforcing proxy (see
// SecurityProxyTestSupport) -- target is the raw instance reflection sets fields on.
public class OrgControllerTest {

    /* Variables */

    private final RecordingOrgFlow flow = new RecordingOrgFlow();
    private final OrgController target = new OrgController();
    private final OrgController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "orgFlow", flow);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void get_asOrgAdmin_returnsFlowDataForPrincipalsOrg() {
        authenticate(7L, UserRole.ORG_ADMIN);

        OrgData result = controller.get();

        assertSame(flow.returned, result);
        assertEquals(7L, flow.getOrgId);
    }

    @Test
    public void get_wrongRole_forbidden() {
        authenticate(7L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, controller::get);
    }

    @Test
    public void get_noPrincipal_denied() {
        // filter chain already blocks unauthenticated calls in the real app; here,
        // @PreAuthorize's own backstop distinguishes "no credentials at all" from a
        // wrong role -- AuthenticationCredentialsNotFoundException, not AccessDeniedException.
        assertThrows(AuthenticationCredentialsNotFoundException.class, controller::get);
    }

    @Test
    public void update_asOrgAdmin_passesFormAndPrincipalsOrgIdToFlow() {
        authenticate(42L, UserRole.ORG_ADMIN);
        OrgForm form = new OrgForm();
        form.setName("Acme Ltd");

        OrgData result = controller.update(form);

        assertSame(flow.returned, result);
        assertEquals(42L, flow.updateOrgId);
        assertSame(form, flow.updateForm);
    }

    @Test
    public void update_wrongRole_forbidden() {
        authenticate(42L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.update(new OrgForm()));
    }

    /* Private Helper Methods */

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
    private static final class RecordingOrgFlow extends OrgFlow {
        private final OrgData returned = new OrgData();
        private Long getOrgId;
        private Long updateOrgId;
        private OrgForm updateForm;

        @Override
        public OrgData get(Long orgId) {
            this.getOrgId = orgId;
            return returned;
        }

        @Override
        public OrgData update(Long orgId, OrgForm form) {
            this.updateOrgId = orgId;
            this.updateForm = form;
            return returned;
        }
    }
}
