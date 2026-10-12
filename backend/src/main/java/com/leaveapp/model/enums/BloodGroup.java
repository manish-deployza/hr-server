package com.leaveapp.model.enums;

// ABO + Rh blood group. Stored by constant name; the API speaks the "A+" style label,
// converted at the Form/Data boundary via fromLabel/labelOf.
public enum BloodGroup {
    A_POS("A+"),
    A_NEG("A-"),
    B_POS("B+"),
    B_NEG("B-"),
    AB_POS("AB+"),
    AB_NEG("AB-"),
    O_POS("O+"),
    O_NEG("O-");

    /* Variables */

    private final String label;

    /* Constructors */

    BloodGroup(String label) {
        this.label = label;
    }

    /* Public Methods */

    public String getLabel() {
        return label;
    }

    // null/blank -> null (cleared); case-insensitive; null for an unknown label too --
    // FieldRules.bloodGroup has already rejected those with a 400 before this runs.
    public static BloodGroup fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        for (BloodGroup g : values()) {
            if (g.label.equalsIgnoreCase(label.trim())) {
                return g;
            }
        }
        return null;
    }

    public static String labelOf(BloodGroup g) {
        return g == null ? null : g.label;
    }
}
