package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.pojo.EmpSensitivePojo;

public class EmpSensitiveTestData {

    /* Public Methods */

    // userId is the user id this sensitive-module row hangs off (no generator, same as
    // EmpTestData.anEmployee).
    public static EmpSensitivePojo aSensitiveRow(Long userId) {
        Instant now = Instant.now();
        EmpSensitivePojo e = new EmpSensitivePojo();
        e.setUserId(userId);
        e.setGender("Prefer not to say");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }
}
