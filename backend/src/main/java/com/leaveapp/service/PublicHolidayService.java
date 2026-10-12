package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// PublicHolidayService -> PublicHolidayRepo -> DB (holiday calendar rows)
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.pojo.PublicHolidayPojo;
import com.leaveapp.repo.PublicHolidayRepo;

// Calendar data plus the working-day count a leave request consumes, and HR
// create/update/delete of the calendar rows themselves. Weekly offs are every
// Sunday (no row per week -- see database-schema.md).
@Service
public class PublicHolidayService {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(PublicHolidayService.class);

    @Autowired
    private PublicHolidayRepo holidayRepo;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<PublicHolidayPojo> list(Long orgId, int year) {
        // date range, not YEAR(holiday_date): stays on the (org_id, holiday_date) unique index
        return holidayRepo.findByOrgIdAndHolidayDateBetween(orgId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PublicHolidayPojo findById(Long orgId, Long id) {
        return holidayRepo.findByIdAndOrgId(id, orgId) //
            .orElseThrow(() -> new NotFoundException("holiday not found id=" + id));
    }

    @Transactional(rollbackFor = Exception.class)
    public PublicHolidayPojo create(Long orgId, String name, LocalDate date) {
        require(name, "name");
        if (date == null) {
            throw new BadRequestException("date is required");
        }
        if (holidayRepo.findByOrgIdAndHolidayDate(orgId, date).isPresent()) {
            throw new ConflictException("a holiday already exists on this date");
        }
        PublicHolidayPojo holiday = new PublicHolidayPojo();
        holiday.setOrgId(orgId);
        holiday.setCreatedAt(Instant.now());
        holiday.setName(name.trim());
        holiday.setHolidayDate(date);
        PublicHolidayPojo saved = holidayRepo.save(holiday);
        log.info("holiday created id={} orgId={} date={}", saved.getId(), orgId, date);
        return saved;
    }

    // PATCH: null field = unchanged.
    @Transactional(rollbackFor = Exception.class)
    public PublicHolidayPojo update(Long orgId, Long id, String name, LocalDate date) {
        PublicHolidayPojo holiday = findById(orgId, id);
        if (name != null) {
            holiday.setName(name.trim());
        }
        if (date != null && !date.equals(holiday.getHolidayDate())) {
            if (holidayRepo.findByOrgIdAndHolidayDate(orgId, date).isPresent()) {
                throw new ConflictException("a holiday already exists on this date");
            }
            holiday.setHolidayDate(date);
        }
        return holiday;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long orgId, Long id) {
        holidayRepo.delete(findById(orgId, id));
        log.info("holiday deleted id={} orgId={}", id, orgId);
    }

    // Days in [start, end] inclusive that are neither a Sunday nor a public holiday.
    // Situation-based leave (maternity et al.) counts raw calendar days instead and
    // never calls this.
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public BigDecimal workingDaysBetween(Long orgId, LocalDate start, LocalDate end) {
        Set<LocalDate> holidays = holidayRepo.findByOrgIdAndHolidayDateBetween(orgId, start, end).stream()
            .map(PublicHolidayPojo::getHolidayDate)
            .collect(Collectors.toSet());
        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d)) {
                days++;
            }
        }
        return BigDecimal.valueOf(days).setScale(1);
    }

    /* Private Helper Methods */

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(field + " is required");
        }
        return value.trim();
    }
}
