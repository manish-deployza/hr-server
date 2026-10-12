package com.leaveapp.pojo;

import java.time.LocalDate;

import com.leaveapp.model.enums.BloodGroup;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Directory Steps 4/5/6 (Personal, Address, Pay & IDs) -- the P2-P4 "sensitive
// module". Separate table from `employee`, not more columns there: every existing
// empSvc.findByUserId/findByOrg/findByManager call already reads whatever's on
// EmpPojo (leave flows, manager views, HR list), which is fine for a job title but
// not for a bank account number. Same blast-radius reasoning as keeping the auth
// entity narrow (jpa-persistence.md #14), applied to the highest-sensitivity
// employee data instead of credentials. See prompts/features/employee-directory.md,
// "Sensitive Module (Steps 4/5/6/9)".
//
// 1:1 with `employee`/`user`, same pattern as EmpPojo itself: the PK *is* the user
// id, no separate generator, assigned by the caller before save. All columns nullable --
// nothing here is collected at signup, and every field is optional by the frontend's own
// design ("declining to answer is a real answer").
@Entity
@Table(name = "employee_sensitive")
@Getter
@Setter
@NoArgsConstructor
public class EmpSensitivePojo extends AuditedPojo {

    /* Variables */

    @Id
    @Column(name = "user_id")
    private Long userId;

    // -- Contact -- moved off `employee` (code review: phone/address are PII, not
    // job-title-grade data -- see the removed EmpPojo.phone/address fields).
    // contactAddress is the free-text self-service address (ProfileForm), distinct
    // from the structured `address` embedded below (Directory Steps 4/5/6/9).

    @Column(nullable = true, length = 20)
    private String phone;

    @Column(name = "contact_address", nullable = true, length = 255)
    private String contactAddress;

    // -- Step 4: Personal --

    @Column(name = "date_of_birth", nullable = true)
    private LocalDate dateOfBirth;

    // Free text by design, not an enum/closed set -- the frontend's own comment: "a
    // fixed list cannot represent everyone, and self-describe is the point."
    @Column(nullable = true, length = 50)
    private String gender;

    // ISO 3166-1 alpha-2, validated via Locale.getISOCountries() in the Flow.
    @Column(nullable = true, length = 2)
    private String nationality;

    @Column(name = "marital_status", nullable = true, length = 40)
    private String maritalStatus;

    // Stored as the constant name (A_POS); the "A+" label is converted at the Form/Data
    // boundary (BloodGroup.fromLabel/labelOf).
    @Enumerated(EnumType.STRING)
    @Column(name = "blood_group", nullable = true, length = 8)
    private BloodGroup bloodGroup;

    @Column(name = "personal_email", nullable = true, length = 190)
    private String personalEmail;

    // -- Step 5: Address + emergency contact --

    // address1/city/country required-together once any address field is filled,
    // postalCode/state conditionally required by country -- enforced in DirectoryFlow,
    // not here (hbm2ddl.auto=update can't add NOT NULL to a populated table).
    @Embedded
    private AddressPojo address = new AddressPojo();

    @Column(name = "emergency_name", nullable = true, length = 128)
    private String emergencyName;

    @Column(name = "emergency_relationship", nullable = true, length = 50)
    private String emergencyRelationship;

    @Column(name = "emergency_phone", nullable = true, length = 20)
    private String emergencyPhone;

    // -- Step 6: Pay & IDs --

    // Plain text, always masked to their last 4 characters in DirectoryData -- no
    // caller, including ORG_ADMIN, ever reads the full value back through the API.
    // bankIfsc is unmasked: a routing code, not a secret.
    // Lengths match FieldRules: 34 = IBAN maximum (bankAccount), 30 = idCode's ceiling.
    @Column(name = "bank_account", nullable = true, length = 34)
    private String bankAccount;

    @Column(name = "bank_ifsc", nullable = true, length = 20)
    private String bankIfsc;

    @Column(name = "national_id", nullable = true, length = 30)
    private String nationalId;

    @Column(name = "tax_id", nullable = true, length = 30)
    private String taxId;

    @Column(name = "passport", nullable = true, length = 30)
    private String passport;

    /* Public Methods */

    // Hibernate nulls out an @Embedded whose columns are all NULL on load (true for
    // every freshly created sensitive row), overriding the field initializer above --
    // so every caller of getAddress() would otherwise have to null-check it themselves.
    public AddressPojo getAddress() {
        if (address == null) {
            address = new AddressPojo();
        }
        return address;
    }
}
