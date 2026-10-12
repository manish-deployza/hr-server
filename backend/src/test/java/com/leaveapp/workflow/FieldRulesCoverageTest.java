package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.leaveapp.exception.BadRequestException;

// Plain unit test, no Spring context. Gap-filler for FieldRulesTest: the null / blank
// pass-through of every rule, each forbidden character on its own, and exact boundaries.
public class FieldRulesCoverageTest {

    /* Public Methods */

    @Test
    public void text_exactlyMaxLength_isAccepted_oneOverIsRejected() {
        assertEquals("x".repeat(10), FieldRules.text("f", "x".repeat(10), 10));
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "x".repeat(11), 10));
    }

    @Test
    public void text_lengthIsMeasuredAfterTrimming() {
        assertEquals("x".repeat(10), FieldRules.text("f", "  " + "x".repeat(10) + "  ", 10));
    }

    @Test
    public void text_eachForbiddenCharacterIsRejectedOnItsOwn() {
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "a>b", 20)); // '>' without '<'
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "a<b", 20)); // '<' without '>'
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "a\tb", 20)); // control char
        assertThrows(BadRequestException.class, () -> FieldRules.text("f", "a\u007fb", 20)); // DEL
    }

    @Test
    public void text_errorNamesTheField() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> FieldRules.text("city", "a<b", 20));
        assertEquals("city contains invalid characters", e.getMessage());
    }

    @Test
    public void text_unicodeLettersAndPunctuationAreAllowed() {
        assertEquals("Zoë D'Souza-Núñez", FieldRules.text("f", "Zoë D'Souza-Núñez", 30));
    }

    @Test
    public void email_nullAndBlank_passThrough() {
        assertNull(FieldRules.email("e", null));
        assertEquals("", FieldRules.email("e", "   "));
    }

    @Test
    public void email_isTrimmed_andOverLongRejected() {
        assertEquals("a@b.co", FieldRules.email("e", "  a@b.co "));
        assertThrows(BadRequestException.class, () -> FieldRules.email("e", "a".repeat(186) + "@b.co")); // 191 chars
    }

    @Test
    public void phone_nullAndBlank_passThrough() {
        assertNull(FieldRules.phone("p", null));
        assertEquals("", FieldRules.phone("p", "  "));
    }

    @Test
    public void phone_digitCountBoundaries() {
        assertEquals("1234567", FieldRules.phone("p", "1234567")); // 7
        assertEquals("123456789012345", FieldRules.phone("p", "123456789012345")); // 15
        assertThrows(BadRequestException.class, () -> FieldRules.phone("p", "123456")); // 6
        assertThrows(BadRequestException.class, () -> FieldRules.phone("p", "1234567890123456")); // 16
    }

    @Test
    public void phone_overTwentyChars_isRejectedByLength() {
        assertThrows(BadRequestException.class, () -> FieldRules.phone("p", "1".repeat(21)));
    }

    @Test
    public void phone_formattingCharactersAreAllowed() {
        assertEquals("+1 (415) 555-0100", FieldRules.phone("p", "+1 (415) 555-0100"));
    }

    @Test
    public void isoCountry_nullAndBlank_passThrough() {
        assertNull(FieldRules.isoCountry("c", null));
        assertEquals("", FieldRules.isoCountry("c", ""));
        assertEquals("", FieldRules.isoCountry("c", "   "));
    }

    @Test
    public void isoCountry_validAndInvalidCodes() {
        assertEquals("US", FieldRules.isoCountry("c", " us "));
        assertThrows(BadRequestException.class, () -> FieldRules.isoCountry("c", "ZZ"));
        assertThrows(BadRequestException.class, () -> FieldRules.isoCountry("c", "USA")); // over 2 chars
        assertThrows(BadRequestException.class, () -> FieldRules.isoCountry("c", "U"));
    }

    @Test
    public void postalCode_nullAndBlank_passThrough() {
        assertNull(FieldRules.postalCode("z", null));
        assertEquals("", FieldRules.postalCode("z", " "));
    }

    @Test
    public void postalCode_lengthBoundaries() {
        assertEquals("ABC", FieldRules.postalCode("z", "ABC")); // 3
        assertEquals("A" + "B".repeat(18) + "C", FieldRules.postalCode("z", "A" + "B".repeat(18) + "C")); // 20
        assertThrows(BadRequestException.class, () -> FieldRules.postalCode("z", "AB")); // 2
        assertThrows(BadRequestException.class, () -> FieldRules.postalCode("z", "A".repeat(21))); // 21
        assertThrows(BadRequestException.class, () -> FieldRules.postalCode("z", "-AB-")); // edge hyphens
    }

    @Test
    public void bloodGroup_nullAndBlank_passThrough() {
        assertNull(FieldRules.bloodGroup("b", null));
        assertEquals("", FieldRules.bloodGroup("b", ""));
    }

    @Test
    public void bloodGroup_everyValidGroupAccepted_caseInsensitively() {
        for (String g : new String[] {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"}) {
            assertEquals(g, FieldRules.bloodGroup("b", g.toLowerCase()));
        }
    }

    @Test
    public void bloodGroup_invalid_listsTheAllowedValues() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> FieldRules.bloodGroup("b", "C+"));
        assertEquals("b must be one of A+, A-, B+, B-, AB+, AB-, O+, O-", e.getMessage());
        assertThrows(BadRequestException.class, () -> FieldRules.bloodGroup("b", "ABC+++")); // over 5 chars
    }

    @Test
    public void ifsc_nullAndBlank_passThrough() {
        assertNull(FieldRules.ifsc("i", null));
        assertEquals("", FieldRules.ifsc("i", "  "));
    }

    @Test
    public void ifsc_validIsUpperCased_invalidShapesRejected() {
        assertEquals("SBIN0ABC123", FieldRules.ifsc("i", "sbin0abc123"));
        assertThrows(BadRequestException.class, () -> FieldRules.ifsc("i", "SBI00ABC123")); // 5th char must be 0
        assertThrows(BadRequestException.class, () -> FieldRules.ifsc("i", "SBIN0ABC12")); // too short
        assertThrows(BadRequestException.class, () -> FieldRules.ifsc("i", "SBIN0ABC1234")); // too long
    }

    @Test
    public void bankAccount_nullAndBlank_passThrough() {
        assertNull(FieldRules.bankAccount("a", null));
        assertEquals("", FieldRules.bankAccount("a", " "));
    }

    @Test
    public void bankAccount_lengthBoundaries() {
        assertEquals("12345", FieldRules.bankAccount("a", "12345")); // 5
        assertEquals("1".repeat(34), FieldRules.bankAccount("a", "1".repeat(34))); // 34
        assertThrows(BadRequestException.class, () -> FieldRules.bankAccount("a", "1234")); // 4
        assertThrows(BadRequestException.class, () -> FieldRules.bankAccount("a", "1".repeat(35))); // 35
        assertThrows(BadRequestException.class, () -> FieldRules.bankAccount("a", "1".repeat(256))); // over column
    }

    @Test
    public void idCode_nullAndBlank_passThrough() {
        assertNull(FieldRules.idCode("t", null));
        assertEquals("", FieldRules.idCode("t", ""));
    }

    @Test
    public void idCode_lengthBoundariesAndAllowedSeparators() {
        assertEquals("AB1C", FieldRules.idCode("t", "ab1c")); // 4
        assertEquals("AB-12/34 56", FieldRules.idCode("t", "ab-12/34 56"));
        assertEquals("A" + "1".repeat(28) + "B", FieldRules.idCode("t", "a" + "1".repeat(28) + "b")); // 30
        assertThrows(BadRequestException.class, () -> FieldRules.idCode("t", "AB1")); // 3
        assertThrows(BadRequestException.class, () -> FieldRules.idCode("t", "A" + "1".repeat(29) + "B")); // 31
        assertThrows(BadRequestException.class, () -> FieldRules.idCode("t", "-AB12")); // leading separator
    }

    @Test
    public void birthDate_nullPassesThrough_boundariesAreInclusiveOfOldestAndYesterday() {
        assertNull(FieldRules.birthDate("d", null));
        assertEquals(LocalDate.of(1900, 1, 1), FieldRules.birthDate("d", LocalDate.of(1900, 1, 1)));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        assertEquals(yesterday, FieldRules.birthDate("d", yesterday));
    }

    @Test
    public void birthDate_todayFutureAndTooOld_areRejected() {
        assertThrows(BadRequestException.class, () -> FieldRules.birthDate("d", LocalDate.now()));
        assertThrows(BadRequestException.class, () -> FieldRules.birthDate("d", LocalDate.now().plusDays(1)));
        assertThrows(BadRequestException.class, () -> FieldRules.birthDate("d", LocalDate.of(1899, 12, 31)));
    }

    @Test
    public void nz_nullBecomesEmpty_valuesAreUntouched() {
        assertEquals("", FieldRules.nz(null));
        assertEquals("", FieldRules.nz(""));
        assertEquals(" x ", FieldRules.nz(" x "));
    }
}
