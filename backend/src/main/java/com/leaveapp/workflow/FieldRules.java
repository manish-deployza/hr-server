package com.leaveapp.workflow;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.enums.BloodGroup;

// Per-field input rules for everything a user or admin types into the invite, onboarding,
// HR-edit and import paths. Every method: null -> null (PATCH "unchanged"), blank -> ""
// (clear / let the required check decide), otherwise trimmed, checked and returned
// normalized. A violation is a 400 naming the field -- never a database error. Lengths
// match the column lengths, so a value that passes here always fits.
final class FieldRules {

    /* Variables */

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]+$");
    private static final Pattern POSTAL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 -]{1,18}[A-Za-z0-9]$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[A-Za-z0-9]{5,34}$");
    private static final Pattern ID_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 /-]{2,28}[A-Za-z0-9]$");
    private static final LocalDate OLDEST_DOB = LocalDate.of(1900, 1, 1);

    /* Constructors */

    private FieldRules() {
    }

    /* Package Methods */

    // Free text: no control characters, no angle brackets (stored-XSS defence in depth --
    // no legitimate name, address or label contains them), at most `max` characters.
    static String text(String field, String v, int max) {
        String t = trimmed(v);
        if (t == null || t.isEmpty()) {
            return t;
        }
        if (t.length() > max) {
            throw new BadRequestException(field + " must be at most " + max + " characters");
        }
        if (t.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>')) {
            throw new BadRequestException(field + " contains invalid characters");
        }
        return t;
    }

    static String email(String field, String v) {
        // 189 = account.email's length: 8 (org_id) + 189 x 4 = 764 bytes keeps
        // uk_account_org_email under MySQL's 767-byte key limit.
        String t = text(field, v, 189);
        return matches(field, t, EMAIL, "must be a valid email address");
    }

    // +, digits, spaces, brackets, hyphens; 7-15 digits (E.164 ceiling), at most 20 chars.
    static String phone(String field, String v) {
        String t = text(field, v, 20);
        if (t == null || t.isEmpty()) {
            return t;
        }
        matches(field, t, PHONE, "must be a phone number");
        long digits = t.chars().filter(Character::isDigit).count();
        if (digits < 7 || digits > 15) {
            throw new BadRequestException(field + " must have 7 to 15 digits");
        }
        return t;
    }

    // ISO 3166-1 alpha-2, upper-cased.
    static String isoCountry(String field, String v) {
        String t = text(field, v, 2);
        if (t == null || t.isEmpty()) {
            return t;
        }
        t = t.toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(t)) {
            throw new BadRequestException(field + " must be a 2-letter country code");
        }
        return t;
    }

    static String postalCode(String field, String v) {
        return matches(field, text(field, v, 20), POSTAL, "must be 3 to 20 letters, digits, spaces or hyphens");
    }

    static String bloodGroup(String field, String v) {
        String t = text(field, v, 5);
        if (t == null || t.isEmpty()) {
            return t;
        }
        BloodGroup g = BloodGroup.fromLabel(t);
        if (g == null) {
            throw new BadRequestException(field + " must be one of " + Arrays.stream(BloodGroup.values())
                .map(BloodGroup::getLabel).collect(Collectors.joining(", ")));
        }
        return g.getLabel();
    }

    static String ifsc(String field, String v) {
        String t = text(field, v, 20);
        return t == null || t.isEmpty() ? t : matches(field, t.toUpperCase(Locale.ROOT), IFSC, "must be a valid IFSC code");
    }

    static String bankAccount(String field, String v) {
        return matches(field, text(field, v, 34), ACCOUNT, "must be 5 to 34 letters or digits");
    }

    // Government / tax / passport numbers: 4-30 letters, digits, spaces, slashes or hyphens.
    // Formats differ by country, so this bounds the shape without pretending to know it.
    static String idCode(String field, String v) {
        String t = text(field, v, 30);
        return t == null || t.isEmpty() ? t : matches(field, t.toUpperCase(Locale.ROOT), ID_CODE,
            "must be 4 to 30 letters, digits, spaces, slashes or hyphens");
    }

    static LocalDate birthDate(String field, LocalDate d) {
        if (d != null && (d.isBefore(OLDEST_DOB) || !d.isBefore(LocalDate.now()))) {
            throw new BadRequestException(field + " must be between 1900-01-01 and yesterday");
        }
        return d;
    }

    // null -> "" so a PUT clears an optional field instead of leaving the old value.
    static String nz(String s) {
        return s == null ? "" : s;
    }

    /* Private Helper Methods */

    private static String trimmed(String v) {
        return v == null ? null : v.trim();
    }

    private static String matches(String field, String t, Pattern p, String msg) {
        if (t != null && !t.isEmpty() && !p.matcher(t).matches()) {
            throw new BadRequestException(field + " " + msg);
        }
        return t;
    }
}
