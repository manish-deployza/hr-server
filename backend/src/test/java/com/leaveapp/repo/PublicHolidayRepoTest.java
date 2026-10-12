package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.PublicHolidayPojo;

public class PublicHolidayRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private PublicHolidayRepo holidayRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        PublicHolidayPojo saved = holidayRepo.save(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 1, 26)));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByOrgIdAndHolidayDate_returnsMatch() {
        holidayRepo.save(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 8, 15)));
        Optional<PublicHolidayPojo> found = holidayRepo.findByOrgIdAndHolidayDate(1L, LocalDate.of(2026, 8, 15));
        assertTrue(found.isPresent());
    }

    @Test
    public void findByOrgIdAndHolidayDate_noMatch_empty() {
        assertTrue(holidayRepo.findByOrgIdAndHolidayDate(1L, LocalDate.of(2026, 12, 25)).isEmpty());
    }

    @Test
    public void findByOrgIdAndHolidayDate_wrongOrg_empty() {
        holidayRepo.save(PublicHolidayTestData.aHoliday(1L, LocalDate.of(2026, 9, 5)));
        assertTrue(holidayRepo.findByOrgIdAndHolidayDate(2L, LocalDate.of(2026, 9, 5)).isEmpty());
    }

    @Test
    public void findByOrgIdAndHolidayDateBetween_isInclusiveBothEnds() {
        holidayRepo.save(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 1, 26)));
        holidayRepo.save(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 8, 15)));
        holidayRepo.save(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 10, 2)));

        List<PublicHolidayPojo> found = holidayRepo.findByOrgIdAndHolidayDateBetween(
            1L, LocalDate.of(2026, 1, 26), LocalDate.of(2026, 8, 15));

        assertEquals(2, found.size());
    }

    @Test
    public void findByOrgIdAndHolidayDateBetween_wrongOrg_empty() {
        holidayRepo.save(PublicHolidayTestData.aHoliday(1L, LocalDate.of(2026, 1, 26)));
        assertTrue(holidayRepo.findByOrgIdAndHolidayDateBetween(
            2L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).isEmpty());
    }

    @Test
    public void findByIdAndOrgId_returnsMatch() {
        PublicHolidayPojo saved = holidayRepo.save(PublicHolidayTestData.aHoliday(1L, LocalDate.of(2026, 4, 14)));
        assertTrue(holidayRepo.findByIdAndOrgId(saved.getId(), 1L).isPresent());
    }

    @Test
    public void findByIdAndOrgId_wrongOrg_empty() {
        PublicHolidayPojo saved = holidayRepo.save(PublicHolidayTestData.aHoliday(1L, LocalDate.of(2026, 4, 15)));
        assertTrue(holidayRepo.findByIdAndOrgId(saved.getId(), 2L).isEmpty());
    }

    @Test
    public void save_nullOrgId_throws() {
        PublicHolidayPojo h = PublicHolidayTestData.aHoliday(LocalDate.of(2026, 3, 4));
        h.setOrgId(null);
        assertThrows(DataIntegrityViolationException.class, () -> holidayRepo.saveAndFlush(h));
    }

    @Test
    public void save_nullHolidayDate_throws() {
        PublicHolidayPojo h = PublicHolidayTestData.aHoliday(LocalDate.of(2026, 3, 1));
        h.setHolidayDate(null);
        assertThrows(DataIntegrityViolationException.class, () -> holidayRepo.saveAndFlush(h));
    }

    @Test
    public void save_nullName_throws() {
        PublicHolidayPojo h = PublicHolidayTestData.aHoliday(LocalDate.of(2026, 3, 2));
        h.setName(null);
        assertThrows(DataIntegrityViolationException.class, () -> holidayRepo.saveAndFlush(h));
    }

    @Test
    public void save_duplicateHolidayDate_throws() {
        holidayRepo.saveAndFlush(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 5, 1)));
        assertThrows(DataIntegrityViolationException.class,
            () -> holidayRepo.saveAndFlush(PublicHolidayTestData.aHoliday(LocalDate.of(2026, 5, 1))));
    }

    @Test
    public void save_sameHolidayDateDifferentOrg_succeeds() {
        holidayRepo.saveAndFlush(PublicHolidayTestData.aHoliday(1L, LocalDate.of(2026, 5, 2)));
        PublicHolidayPojo saved = holidayRepo.saveAndFlush(PublicHolidayTestData.aHoliday(2L, LocalDate.of(2026, 5, 2)));
        assertNotNull(saved.getId());
    }

    @Test
    public void save_nullCreatedAt_throws() {
        PublicHolidayPojo h = PublicHolidayTestData.aHoliday(LocalDate.of(2026, 3, 3));
        h.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> holidayRepo.saveAndFlush(h));
    }
}
