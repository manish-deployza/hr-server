package com.leaveapp.model.form;


import lombok.Getter;
import lombok.Setter;

// Pay & IDs step (onboarding PUT /api/onboarding/pay-ids, and the HR/admin edit).
// nationalId is the government ID (Aadhaar), taxId the tax ID (PAN); passport is optional.
@Getter
@Setter
public class PayIdsForm {

    /* Variables */

    private String bankAccount;
    private String bankIfsc;
    private String nationalId;
    private String taxId;
    private String passport;
}
