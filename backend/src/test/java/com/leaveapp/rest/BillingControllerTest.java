package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.leaveapp.model.data.SubscriptionData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.BillingFlow;

// Plain unit test -- no Spring/web context (TestConfig deliberately excludes
// controllers), same shape as OrgControllerTest. Covers /api/billing/cancel: the
// ORG_ADMIN gate and "orgId from the JWT principal" rule -- the actual cancel logic
// (subscribe to FREE) is BillingFlow/SubscriptionService's job and is already covered
// there.
public class BillingControllerTest {

    /* Variables */

    private final RecordingBillingFlow flow = new RecordingBillingFlow();
    private final BillingController target = new BillingController();
    private final BillingController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "billingFlow", flow);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void cancel_asOrgAdmin_returnsFlowDataForPrincipalsOrg() {
        authenticate(7L, UserRole.ORG_ADMIN);

        SubscriptionData result = controller.cancel();

        assertSame(flow.returned, result);
        assertEquals(7L, flow.cancelOrgId);
    }

    @Test
    public void cancel_wrongRole_forbidden() {
        authenticate(7L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, controller::cancel);
    }

    @Test
    public void cancel_noPrincipal_denied() {
        // filter chain already blocks unauthenticated calls in the real app; here,
        // @PreAuthorize's own backstop distinguishes "no credentials at all" from a
        // wrong role -- AuthenticationCredentialsNotFoundException, not AccessDeniedException.
        assertThrows(AuthenticationCredentialsNotFoundException.class, controller::cancel);
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
    private static final class RecordingBillingFlow extends BillingFlow {
        private final SubscriptionData returned = new SubscriptionData();
        private Long cancelOrgId;

        @Override
        public SubscriptionData cancel(Long orgId) {
            this.cancelOrgId = orgId;
            return returned;
        }
    }
}
