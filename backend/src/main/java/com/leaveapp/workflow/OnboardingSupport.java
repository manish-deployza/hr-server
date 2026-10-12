package com.leaveapp.workflow;

import java.util.ArrayList;
import java.util.List;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.pojo.EmpSensitivePojo;

// Which fields each onboarding step requires, checked against the stored row (so the
// same rule drives "is this step done?", the per-step save, and the final submit).
// Optional on purpose: blood group, address line 2, landmark, passport. No format
// rules here -- format and length rules live in FieldRules (applied before this check).
final class OnboardingSupport {

    /* Constructors */

    private OnboardingSupport() {
    }

    /* Package Methods */

    static List<String> missingPersonal(EmpSensitivePojo s) {
        List<String> m = new ArrayList<>();
        check(m, "phone", s.getPhone());
        check(m, "dateOfBirth", s.getDateOfBirth());
        check(m, "gender", s.getGender());
        check(m, "nationality", s.getNationality());
        check(m, "maritalStatus", s.getMaritalStatus());
        check(m, "personalEmail", s.getPersonalEmail());
        return m;
    }

    static List<String> missingAddress(EmpSensitivePojo s) {
        List<String> m = new ArrayList<>();
        check(m, "address1", s.getAddress().getAddress1());
        check(m, "city", s.getAddress().getCity());
        check(m, "state", s.getAddress().getState());
        check(m, "postalCode", s.getAddress().getPostalCode());
        check(m, "country", s.getAddress().getCountry());
        return m;
    }

    static List<String> missingEmergency(EmpSensitivePojo s) {
        List<String> m = new ArrayList<>();
        check(m, "emergencyName", s.getEmergencyName());
        check(m, "emergencyRelationship", s.getEmergencyRelationship());
        check(m, "emergencyPhone", s.getEmergencyPhone());
        return m;
    }

    static List<String> missingPay(EmpSensitivePojo s) {
        List<String> m = new ArrayList<>();
        check(m, "bankAccount", s.getBankAccount());
        check(m, "bankIfsc", s.getBankIfsc());
        check(m, "nationalId", s.getNationalId());
        check(m, "taxId", s.getTaxId());
        return m;
    }

    static void require(String step, List<String> missing) {
        if (!missing.isEmpty()) {
            throw new BadRequestException(step + ": " + String.join(", ", missing) + " required");
        }
    }


    /* Private Helper Methods */

    private static void check(List<String> missing, String field, String value) {
        if (value == null || value.isBlank()) {
            missing.add(field);
        }
    }

    private static void check(List<String> missing, String field, Object value) {
        if (value == null) {
            missing.add(field);
        }
    }
}
