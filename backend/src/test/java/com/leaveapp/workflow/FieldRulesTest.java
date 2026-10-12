package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.form.OnboardingPersonalForm;
import com.leaveapp.model.form.PayIdsForm;
import com.leaveapp.model.form.UserInviteForm;

// Plain unit test of the per-field input rules -- no Spring context.
public class FieldRulesTest {

    /* Public Methods */

    @Test
    public void nullStaysNull_blankBecomesEmpty_valueIsTrimmed() {
        assertNull(FieldRules.text("f", null, 10));
        assertEquals("", FieldRules.text("f", "   ", 10));
        assertEquals("abc", FieldRules.text("f", "  abc ", 10));
    }

    @Test
    public void text_rejectsTooLong_controlChars_andAngleBrackets() {
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "x".repeat(11), 10));
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "a\u0000b", 10));
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "a\nb", 10));
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "<b>x</b>", 20));
        assertEquals("O'Neil-Smith", FieldRules.text("f", "O'Neil-Smith", 20));
    }

    @Test
    public void email_shapeCheck() {
        assertEquals("a@b.co", FieldRules.email("e", "a@b.co"));
        for (String bad : new String[] {"plain", "a@b", "a b@c.de", "@b.co", "a@@b.co"}) {
            assertThrows(BadRequestException.class, () -> FieldRules.email("e", bad));
        }
    }

    @Test
    public void phone_digitsAndShape() {
        assertEquals("+91 98765 43210", FieldRules.phone("p", "+91 98765 43210"));
        assertThrows(BadRequestException.class, () -> FieldRules.phone("p", "12345"));           // too few digits
        assertThrows(BadRequestException.class, () -> FieldRules.phone("p", "1234567890123456")); // too many
        assertThrows(BadRequestException.class, () -> FieldRules.phone("p", "98765abc10"));
    }

    @Test
    public void country_bloodGroup_postal_ifsc_ids() {
        assertEquals("IN", FieldRules.isoCountry("c", "in"));
        assertThrows(BadRequestException.class, () -> FieldRules.isoCountry("c", "XX"));
        assertEquals("AB+", FieldRules.bloodGroup("b", "ab+"));
        assertThrows(BadRequestException.class, () -> FieldRules.bloodGroup("b", "Z+"));
        assertEquals("560 001", FieldRules.postalCode("z", "560 001"));
        assertThrows(BadRequestException.class, () -> FieldRules.postalCode("z", "5"));
        assertEquals("HDFC0001234", FieldRules.ifsc("i", "hdfc0001234"));
        assertThrows(BadRequestException.class, () -> FieldRules.ifsc("i", "HDFC1001234"));
        assertThrows(BadRequestException.class, () -> FieldRules.bankAccount("a", "12 34"));
        assertEquals("ABCDE1234F", FieldRules.idCode("t", "abcde1234f"));
        assertThrows(BadRequestException.class, () -> FieldRules.idCode("t", "AB"));
        assertThrows(BadRequestException.class, () -> FieldRules.idCode("t", "AB;DROP TABLE x"));
    }

    @Test
    public void birthDate_boundsAreOneNineHundredToYesterday() {
        assertEquals(LocalDate.of(1990, 1, 1), FieldRules.birthDate("d", LocalDate.of(1990, 1, 1)));
        assertThrows(BadRequestException.class, () -> FieldRules.birthDate("d", LocalDate.of(1899, 12, 31)));
        assertThrows(BadRequestException.class, () -> FieldRules.birthDate("d", LocalDate.now()));
        assertNull(FieldRules.birthDate("d", null));
    }

    // Tamper-proofing: the new request bodies carry only the fields they name, so an extra
    // property (status, role on a details form, userId ...) is a 400, never silently applied.
    @Test
    public void newForms_rejectUnknownProperties() {
        ObjectMapper m = new ObjectMapper();
        assertThrows(UnrecognizedPropertyException.class, () -> m.readValue(
            "{\"name\":\"x\",\"email\":\"a@b.co\",\"role\":\"HR\",\"status\":\"ACTIVE\"}", UserInviteForm.class));
        assertThrows(UnrecognizedPropertyException.class, () -> m.readValue(
            "{\"phone\":\"1234567\",\"userId\":5}", OnboardingPersonalForm.class));
        assertThrows(UnrecognizedPropertyException.class, () -> m.readValue(
            "{\"bankAccount\":\"12345\",\"orgId\":1}", PayIdsForm.class));
    }
}
