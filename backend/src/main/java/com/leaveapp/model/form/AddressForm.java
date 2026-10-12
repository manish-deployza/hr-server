package com.leaveapp.model.form;

import com.leaveapp.pojo.AddressPojo;

import lombok.Getter;
import lombok.Setter;

// Shared address value object -- see AddressPojo (the persistence-layer @Embeddable this
// mirrors). Reused via @JsonUnwrapped by every *Form that carries a structured address
// instead of repeating these 7 fields loose.
@Getter
@Setter
public class AddressForm {

    /* Variables */

    private String address1;
    private String address2;
    private String city;
    private String landmark;
    private String postalCode;
    private String state;
    private String country;

    /* Public Methods */

    public AddressPojo toPojo() {
        AddressPojo p = new AddressPojo();
        p.setAddress1(address1);
        p.setAddress2(address2);
        p.setCity(city);
        p.setLandmark(landmark);
        p.setPostalCode(postalCode);
        p.setState(state);
        p.setCountry(country);
        return p;
    }
}
