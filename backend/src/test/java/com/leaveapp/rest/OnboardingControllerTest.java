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

import com.leaveapp.model.data.OnboardingData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.OnboardingFlow;

// Plain unit test, same style as AdminUserControllerTest: only the PENDING-only
// ROLE_ONBOARDING authority gets in; every real role is rejected, and the caller's own id
// (from the token) is what reaches the Flow.
public class OnboardingControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final OnboardingController target = new OnboardingController();
    private final OnboardingController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "flow", flow);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void onboardingSession_reachesFlowWithOwnUserId() {
        authenticate("ROLE_ONBOARDING");
        assertSame(flow.returns, controller.savePersonal(new OnboardingPersonalForm()));
        assertSame(flow.returns, controller.get());
        controller.submit();
        assertEquals(1L, flow.submittedUserId);
    }

    @Test
    public void everyRealRole_forbidden() {
        for (UserRole role : UserRole.values()) {
            authenticate("ROLE_" + role.name());
            assertThrows(AccessDeniedException.class, controller::get);
            assertThrows(AccessDeniedException.class, controller::submit);
        }
    }

    /* Private Helper Methods */

    private static void authenticate(String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(9L);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, authorities));
    }

    /* Test Doubles */

    private static final class RecordingFlow extends OnboardingFlow {
        private final OnboardingData returns = new OnboardingData();
        private Long submittedUserId;

        @Override
        public OnboardingData get(Long userId) {
            return returns;
        }

        @Override
        public OnboardingData savePersonal(Long userId, OnboardingPersonalForm form) {
            return returns;
        }

        @Override
        public void submit(Long userId) {
            this.submittedUserId = userId;
        }
    }
}
