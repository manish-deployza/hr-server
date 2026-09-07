package com.leaveapp.model.data;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

// One row of GET /api/hr/holidays. Lombok's isNonNegotiable() serializes as
// "nonNegotiable" -- true only for the statutory national holidays.
@Getter
@Setter
public class HolidayData {

    /* Variables */

    private Long id;
    private String name;
    private LocalDate date;
    private int year;
    private boolean nonNegotiable;
}
