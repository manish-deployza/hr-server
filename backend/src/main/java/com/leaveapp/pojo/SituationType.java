package com.leaveapp.pojo;

// maternity sub-case, applies only when the leave type is situation-based -- see
// prompts/schema.md#leave_request. TWO_OR_MORE_CHILDREN is the API/DB wire value
// "2_OR_MORE_CHILDREN" (a Java identifier can't start with a digit) -- use wireValue()
// / fromWireValue() rather than name()/valueOf() at any boundary (DB column, JSON).
public enum SituationType {
    UP_TO_2_CHILDREN,
    TWO_OR_MORE_CHILDREN,
    ADOPTION_UNDER_3MO,
    COMMISSIONING_MOTHER,
    MISCARRIAGE,
    PREGNANCY_ILLNESS_EXTENSION;

    /* Public Methods */

    public String wireValue() {
        return this == TWO_OR_MORE_CHILDREN ? "2_OR_MORE_CHILDREN" : name();
    }

    public static SituationType fromWireValue(String value) {
        return "2_OR_MORE_CHILDREN".equals(value) ? TWO_OR_MORE_CHILDREN : SituationType.valueOf(value);
    }
}
