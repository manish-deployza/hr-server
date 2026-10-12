package com.leaveapp.repo;

import java.time.Instant;
import java.time.LocalDate;

import com.leaveapp.pojo.PublicHolidayPojo;

public class PublicHolidayTestData {

    /* Public Methods */

    public static PublicHolidayPojo aHoliday(LocalDate date) {
        return aHoliday(1L, date);
    }

    public static PublicHolidayPojo aHoliday(Long orgId, LocalDate date) {
        PublicHolidayPojo h = new PublicHolidayPojo();
        h.setOrgId(orgId);
        h.setHolidayDate(date);
        h.setName("Test Holiday");
        h.setCreatedAt(Instant.now());
        return h;
    }
}
