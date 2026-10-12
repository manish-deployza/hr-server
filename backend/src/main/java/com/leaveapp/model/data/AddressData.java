package com.leaveapp.model.data;

import com.leaveapp.pojo.AddressPojo;

import lombok.Getter;
import lombok.Setter;

// Response-side twin of AddressForm/AddressPojo -- see AddressForm for why this is a
// separate class rather than reusing the persistence-layer AddressPojo directly.
@Getter
@Setter
public class AddressData {

    /* Variables */

    private String address1;
    private String address2;
    private String city;
    private String landmark;
    private String postalCode;
    private String state;
    private String country;

    /* Public Methods */

    public static AddressData from(AddressPojo p) {
        AddressData d = new AddressData();
        d.setAddress1(p.getAddress1());
        d.setAddress2(p.getAddress2());
        d.setCity(p.getCity());
        d.setLandmark(p.getLandmark());
        d.setPostalCode(p.getPostalCode());
        d.setState(p.getState());
        d.setCountry(p.getCountry());
        return d;
    }
}
