package com.leaveapp.model.form;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

// Request body for POST / PATCH /api/hr/holidays.
// PATCH semantics: a field left out of the JSON stays null and is not changed.
// No `year` field -- it's derived from `date` (PublicHolidayPojo.year is a
// denormalized column, not independently settable).
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class HolidayForm {

    /* Variables */

    private String name;
    private LocalDate date;
}
