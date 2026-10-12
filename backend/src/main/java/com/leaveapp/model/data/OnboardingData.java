package com.leaveapp.model.data;

import lombok.Getter;
import lombok.Setter;

// GET /api/onboarding (also the response of every save) -- the caller's own entries, so
// nothing is masked, plus per-step completion so the frontend can resume and preview.
// nextStep is the first incomplete of personal (details + address) | emergency | payIds, or "review"
// once all three are done.
@Getter
@Setter
public class OnboardingData {

    /* Variables */

    private String name;
    private String email;
    private String status;
    private String nextStep;

    private boolean personalDone; // personal details and address together
    private boolean emergencyDone;
    private boolean payIdsDone;

    private String phone;
    private PersonalInfoData personalInfo;
    private AddressData address;
    private EmergencyContactData emergencyContact;

    private String bankAccount;
    private String bankIfsc;
    private String nationalId;
    private String taxId;
    private String passport;
}
