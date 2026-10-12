package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.model.enums.OrgStatus;

public class OrgTestData {

    /* Public Methods */

    public static OrgPojo anOrg(String orgKey) {
        OrgPojo o = new OrgPojo();
        o.setName("Test Org");
        o.setOrgKey(orgKey);
        o.setHrNotifyEmail("hr@" + orgKey + ".test");
        o.setCountry("IN");
        o.setStatus(OrgStatus.ACTIVE);
        o.setCreatedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return o;
    }
}
