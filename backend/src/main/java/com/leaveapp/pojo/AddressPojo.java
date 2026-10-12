package com.leaveapp.pojo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

// The structured home address (spec §2a in prompts/features/employee-directory.md),
// embedded inside EmpSensitivePojo. All 8 columns nullable at the DB --
// hbm2ddl.auto=update cannot add NOT NULL to a populated table, so address1/city/country
// (required once any address field is filled) and the country-conditional postalCode/
// state are enforced in DirectoryFlow, not here.
@Embeddable
@Getter
@Setter
public class AddressPojo {

    /* Variables */

    @Column(name = "address1", nullable = true, length = 150)
    private String address1;

    @Column(name = "address2", nullable = true, length = 150)
    private String address2;

    @Column(name = "city", nullable = true, length = 100)
    private String city;

    // Neighbourhood/sub-locality -- kept for LATAM/India/MEA deliverability. Never
    // rejected for a country where the frontend hides the input; that's a display rule.
    @Column(name = "landmark", nullable = true, length = 150)
    private String landmark;

    // Stored exactly as entered (spaces/hyphens preserved, e.g. "SW1A 1AA") -- no
    // normalized second column, since nothing searches or filters on it.
    @Column(name = "postal_code", nullable = true, length = 20)
    private String postalCode;

    // ISO 3166-2 subdivision codes aren't enforceable from the JDK (Locale ships country
    // codes only) -- stored as typed, validated only for presence where the country needs one.
    @Column(name = "state", nullable = true, length = 100)
    private String state;

    // ISO 3166-1 alpha-2, upper-cased. Never a free-text country name.
    @Column(name = "country", nullable = true, length = 2)
    private String country;
}
