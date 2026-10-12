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
import com.leaveapp.model.data.PlanData;
import com.leaveapp.model.data.SubscriptionData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.SubscribeForm;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.BillingFlow;

// Companion to BillingControllerTest (which only covers cancel): the other three
// endpoints, the ORG_ADMIN-only gate on all four, and the missing-org-claim guard.
public class BillingControllerCoverageTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
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
    public void plans_returnsCatalogForOrgAdmin() {
        authenticate(7L, "ROLE_ORG_ADMIN");
        assertSame(flow.plans, controller.plans());
        assertEquals(List.of("plans"), flow.log);
    }

    @Test
    public void subscription_passesPrincipalsOrg() {
        authenticate(7L, "ROLE_ORG_ADMIN");
        assertSame(flow.sub, controller.subscription());
        assertEquals(List.of("current:7"), flow.log);
    }

    @Test
    public void subscribe_passesPrincipalsOrgAndForm() {
        authenticate(7L, "ROLE_ORG_ADMIN");
        SubscribeForm form = new SubscribeForm();
        form.setPlanCode("PRO");

        assertSame(flow.sub, controller.subscribe(form));
        assertEquals(List.of("subscribe:7:PRO"), flow.log);
    }

    @Test
    public void everyEndpoint_nonOrgAdmin_forbidden() {
        for (String authority : List.of("ROLE_HR", "ROLE_EMPLOYEE", "ROLE_ONBOARDING")) {
            authenticate(7L, authority);
            assertThrows(AccessDeniedException.class, controller::plans);
            assertThrows(AccessDeniedException.class, controller::subscription);
            assertThrows(AccessDeniedException.class, () -> controller.subscribe(new SubscribeForm()));
            assertThrows(AccessDeniedException.class, controller::cancel);
        }
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void everyEndpoint_anonymous_rejected() {
        assertThrows(Exception.class, controller::plans);
        assertThrows(Exception.class, controller::subscription);
        assertThrows(Exception.class, () -> controller.subscribe(new SubscribeForm()));
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void everyEndpoint_principalWithoutOrgId_notAuthenticated() {
        authenticate(null, "ROLE_ORG_ADMIN");
        assertThrows(NotAuthException.class, controller::plans);
        assertThrows(NotAuthException.class, controller::subscription);
        assertThrows(NotAuthException.class, () -> controller.subscribe(new SubscribeForm()));
        assertThrows(NotAuthException.class, controller::cancel);
        assertTrue(flow.log.isEmpty());
    }

    /* Private Helper Methods */

    private static void authenticate(Long orgId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(orgId);
        p.setRoles(List.of(UserRole.ORG_ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class RecordingFlow extends BillingFlow {
        private final List<String> log = new ArrayList<>();
        private final List<PlanData> plans = List.of(new PlanData());
        private final SubscriptionData sub = new SubscriptionData();

        @Override
        public List<PlanData> plans() {
            log.add("plans");
            return plans;
        }

        @Override
        public SubscriptionData current(Long orgId) {
            log.add("current:" + orgId);
            return sub;
        }

        @Override
        public SubscriptionData subscribe(Long orgId, SubscribeForm form) {
            log.add("subscribe:" + orgId + ":" + form.getPlanCode());
            return sub;
        }
    }
}
