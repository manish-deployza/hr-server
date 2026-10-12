package com.leaveapp.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.OnboardingData;
import com.leaveapp.model.form.EmergencyContactForm;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.workflow.OnboardingFlow;

// The invited user's onboarding form. ROLE_ONBOARDING is only ever held by a PENDING
// user's session (JwtAuthFilter), so this is unreachable before the password is set and
// after submit; it is also the only thing that session can reach. Always the caller's own
// row. Never logs the request bodies -- they carry bank and ID numbers.
@RestController
@RequestMapping("/api/onboarding")
@PreAuthorize("hasRole('ONBOARDING')")
public class OnboardingController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(OnboardingController.class);

    @Autowired
    private OnboardingFlow flow;

    /* Public Methods */

    // Saved entries, per-step completion and the next step to show -- also the preview page.
    @GetMapping
    public OnboardingData get() {
        return flow.get(requireUserId());
    }

    @PutMapping("/personal")
    public OnboardingData savePersonal(@RequestBody OnboardingPersonalForm form) {
        Long userId = requireUserId();
        log.info("PUT /api/onboarding/personal userId={}", userId);
        return flow.savePersonal(userId, form);
    }

    @PutMapping("/emergency")
    public OnboardingData saveEmergency(@RequestBody EmergencyContactForm form) {
        Long userId = requireUserId();
        log.info("PUT /api/onboarding/emergency userId={}", userId);
        return flow.saveEmergency(userId, form);
    }

    @PutMapping("/pay-ids")
    public OnboardingData savePayIds(@RequestBody PayIdsForm form) {
        Long userId = requireUserId();
        log.info("PUT /api/onboarding/pay-ids userId={}", userId);
        return flow.savePayIds(userId, form);
    }

    // Final submit: locks the form (the onboarding session is revoked), so the frontend
    // sends the user to the login form next.
    @PostMapping("/submit")
    public AckData submit() {
        Long userId = requireUserId();
        log.info("POST /api/onboarding/submit userId={}", userId);
        flow.submit(userId);
        return new AckData();
    }
}
