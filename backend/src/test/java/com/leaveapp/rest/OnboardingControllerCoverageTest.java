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
import com.leaveapp.model.data.OnboardingData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.workflow.OnboardingFlow;

// Companion to OnboardingControllerTest (which leaves saveEmergency / savePayIds and the
// missing-user-id guard untouched): every save step passes the caller's own id and form to
// the Flow, and a token without a user id is rejected before the Flow is reached.
public class OnboardingControllerCoverageTest {

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
    public void saveEmergency_passesOwnUserIdAndForm() {
        authenticate(4L, "ROLE_ONBOARDING");
        EmergencyContactForm form = new EmergencyContactForm();

        assertSame(flow.returns, controller.saveEmergency(form));
        assertEquals(List.of("emergency:4"), flow.log);
        assertSame(form, flow.lastForm);
    }

    @Test
    public void savePayIds_passesOwnUserIdAndForm() {
        authenticate(4L, "ROLE_ONBOARDING");
        PayIdsForm form = new PayIdsForm();

        assertSame(flow.returns, controller.savePayIds(form));
        assertEquals(List.of("payIds:4"), flow.log);
        assertSame(form, flow.lastForm);
    }

    @Test
    public void everyStep_realRolesAndAnonymous_rejected() {
        for (UserRole role : UserRole.values()) {
            authenticate(4L, "ROLE_" + role.name());
            assertThrows(AccessDeniedException.class, () -> controller.saveEmergency(new EmergencyContactForm()));
            assertThrows(AccessDeniedException.class, () -> controller.savePayIds(new PayIdsForm()));
            assertThrows(AccessDeniedException.class, () -> controller.savePersonal(new OnboardingPersonalForm()));
        }
        SecurityContextHolder.clearContext();
        assertThrows(Exception.class, controller::get);
        assertTrue(flow.log.isEmpty());
    }

    @Test
    public void everyStep_principalWithoutUserId_notAuthenticated() {
        authenticate(null, "ROLE_ONBOARDING");
        assertThrows(NotAuthException.class, controller::get);
        assertThrows(NotAuthException.class, () -> controller.savePersonal(new OnboardingPersonalForm()));
        assertThrows(NotAuthException.class, () -> controller.saveEmergency(new EmergencyContactForm()));
        assertThrows(NotAuthException.class, () -> controller.savePayIds(new PayIdsForm()));
        assertThrows(NotAuthException.class, controller::submit);
        assertTrue(flow.log.isEmpty());
    }

    /* Private Helper Methods */

    private static void authenticate(Long userId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(userId);
        p.setOrgId(9L);
        p.setRoles(List.of(UserRole.EMPLOYEE));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class RecordingFlow extends OnboardingFlow {
        private final List<String> log = new ArrayList<>();
        private final OnboardingData returns = new OnboardingData();
        private Object lastForm;

        @Override
        public OnboardingData saveEmergency(Long userId, EmergencyContactForm form) {
            log.add("emergency:" + userId);
            lastForm = form;
            return returns;
        }

        @Override
        public OnboardingData savePayIds(Long userId, PayIdsForm form) {
            log.add("payIds:" + userId);
            lastForm = form;
            return returns;
        }
    }
}
