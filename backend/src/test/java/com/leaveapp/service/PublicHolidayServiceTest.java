package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.pojo.PublicHolidayPojo;

// Holiday calendar CRUD (validation, duplicate-date guard, org scoping, PATCH semantics)
// and the working-day count (Sundays + holidays excluded, inclusive range).
public class PublicHolidayServiceTest extends RepositoryTestSupport {

    /* Variables */

    // 2031-03-03 is a Monday.
    private static final LocalDate MON = LocalDate.of(2031, 3, 3);

    @Autowired
    private PublicHolidayService holidaySvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void create_trimsName_setsYear_andListFiltersByYearAndOrg() {
        Long orgId = org("ph-1");
        Long other = org("ph-1b");

        PublicHolidayPojo h = holidaySvc.create(orgId, "  Holi  ", LocalDate.of(2031, 3, 14));
        holidaySvc.create(orgId, "Next Year", LocalDate.of(2032, 1, 1));
        holidaySvc.create(other, "Elsewhere", LocalDate.of(2031, 3, 14));

        assertEquals("Holi", h.getName());
        assertEquals(LocalDate.of(2031, 3, 14), h.getHolidayDate());
        assertEquals(1, holidaySvc.list(orgId, 2031).size());
        assertEquals(1, holidaySvc.list(orgId, 2032).size());
        assertTrue(holidaySvc.list(orgId, 2030).isEmpty());
    }

    @Test
    public void create_blankOrNullName_badRequest() {
        Long orgId = org("ph-2");

        assertThrows(BadRequestException.class, () -> holidaySvc.create(orgId, null, MON));
        assertThrows(BadRequestException.class, () -> holidaySvc.create(orgId, "   ", MON));
    }

    @Test
    public void create_nullDate_badRequest() {
        Long orgId = org("ph-3");

        assertThrows(BadRequestException.class, () -> holidaySvc.create(orgId, "Holi", null));
    }

    @Test
    public void create_duplicateDateSameOrg_conflict_otherOrgAllowed() {
        Long orgId = org("ph-4");
        Long other = org("ph-4b");
        holidaySvc.create(orgId, "Holi", MON);

        assertThrows(ConflictException.class, () -> holidaySvc.create(orgId, "Another", MON));
        assertEquals(MON, holidaySvc.create(other, "Holi", MON).getHolidayDate());
    }

    @Test
    public void findById_scopedToOrg() {
        Long orgId = org("ph-5");
        Long other = org("ph-5b");
        Long id = holidaySvc.create(orgId, "Holi", MON).getId();

        assertEquals(id, holidaySvc.findById(orgId, id).getId());
        assertThrows(NotFoundException.class, () -> holidaySvc.findById(other, id));
        assertThrows(NotFoundException.class, () -> holidaySvc.findById(orgId, -1L));
    }

    @Test
    public void update_nullFields_changesNothing() {
        Long orgId = org("ph-6");
        Long id = holidaySvc.create(orgId, "Holi", MON).getId();

        PublicHolidayPojo h = holidaySvc.update(orgId, id, null, null);

        assertEquals("Holi", h.getName());
        assertEquals(MON, h.getHolidayDate());
    }

    @Test
    public void update_sameDate_isNotAConflictWithItself() {
        Long orgId = org("ph-7");
        Long id = holidaySvc.create(orgId, "Holi", MON).getId();

        PublicHolidayPojo h = holidaySvc.update(orgId, id, " Holi 2 ", MON);

        assertEquals("Holi 2", h.getName());
        assertEquals(MON, h.getHolidayDate());
    }

    @Test
    public void update_newFreeDate_movesHolidayAndYear() {
        Long orgId = org("ph-8");
        Long id = holidaySvc.create(orgId, "NYE", LocalDate.of(2031, 12, 31)).getId();

        PublicHolidayPojo h = holidaySvc.update(orgId, id, null, LocalDate.of(2032, 1, 1));

        assertEquals(LocalDate.of(2032, 1, 1), h.getHolidayDate());
    }

    @Test
    public void update_dateTakenByAnotherHoliday_conflict() {
        Long orgId = org("ph-9");
        holidaySvc.create(orgId, "A", MON);
        Long b = holidaySvc.create(orgId, "B", MON.plusDays(1)).getId();

        assertThrows(ConflictException.class, () -> holidaySvc.update(orgId, b, null, MON));
    }

    @Test
    public void update_otherOrg_notFound() {
        Long orgId = org("ph-10");
        Long other = org("ph-10b");
        Long id = holidaySvc.create(orgId, "A", MON).getId();

        assertThrows(NotFoundException.class, () -> holidaySvc.update(other, id, "X", null));
    }

    @Test
    public void delete_removesHoliday_otherOrgNotFound() {
        Long orgId = org("ph-11");
        Long other = org("ph-11b");
        Long id = holidaySvc.create(orgId, "A", MON).getId();

        assertThrows(NotFoundException.class, () -> holidaySvc.delete(other, id));
        holidaySvc.delete(orgId, id);

        assertThrows(NotFoundException.class, () -> holidaySvc.findById(orgId, id));
    }

    @Test
    public void workingDaysBetween_monToSat_countsSixDays() {
        Long orgId = org("ph-12");

        assertEquals(0, new BigDecimal("6.0").compareTo(holidaySvc.workingDaysBetween(orgId, MON, MON.plusDays(5))));
    }

    @Test
    public void workingDaysBetween_skipsSundays() {
        Long orgId = org("ph-13");

        // Mon..next Mon inclusive = 8 days, one Sunday
        assertEquals(0, new BigDecimal("7.0").compareTo(holidaySvc.workingDaysBetween(orgId, MON, MON.plusDays(7))));
        // Sunday alone = 0
        assertEquals(0, new BigDecimal("0.0").compareTo(
            holidaySvc.workingDaysBetween(orgId, MON.plusDays(6), MON.plusDays(6))));
    }

    @Test
    public void workingDaysBetween_excludesOrgHolidaysOnly_andHolidayOnSundayCountsOnce() {
        Long orgId = org("ph-14");
        Long other = org("ph-14b");
        holidaySvc.create(orgId, "Midweek", MON.plusDays(2));
        holidaySvc.create(orgId, "On Sunday", MON.plusDays(6));
        holidaySvc.create(other, "Other org", MON.plusDays(1));

        // Mon..Mon+7: 8 days - 1 Sunday(also a holiday) - 1 midweek holiday = 6
        assertEquals(0, new BigDecimal("6.0").compareTo(holidaySvc.workingDaysBetween(orgId, MON, MON.plusDays(7))));
    }

    @Test
    public void workingDaysBetween_holidayOnRangeEdges_isExcluded() {
        Long orgId = org("ph-15");
        holidaySvc.create(orgId, "First", MON);
        holidaySvc.create(orgId, "Last", MON.plusDays(2));

        assertEquals(0, new BigDecimal("1.0").compareTo(holidaySvc.workingDaysBetween(orgId, MON, MON.plusDays(2))));
    }

    @Test
    public void workingDaysBetween_startAfterEnd_isZero() {
        Long orgId = org("ph-16");

        assertEquals(0, new BigDecimal("0.0").compareTo(holidaySvc.workingDaysBetween(orgId, MON.plusDays(1), MON)));
    }

    /* Private Helper Methods */

    private Long org(String key) {
        return orgSvc.create("Acme", key, "hr@" + key + ".test", "IN").getId();
    }
}
