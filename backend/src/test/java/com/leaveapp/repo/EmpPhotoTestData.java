package com.leaveapp.repo;

import java.time.Instant;

import com.leaveapp.pojo.EmpPhotoPojo;

public class EmpPhotoTestData {

    /* Public Methods */

    // userId is the user id the photo hangs off (no generator, same as EmpTestData.anEmployee).
    public static EmpPhotoPojo aPhoto(Long userId) {
        Instant now = Instant.now();
        EmpPhotoPojo p = new EmpPhotoPojo();
        p.setUserId(userId);
        p.setBytes(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 });
        p.setContentType("image/jpeg");
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return p;
    }
}
