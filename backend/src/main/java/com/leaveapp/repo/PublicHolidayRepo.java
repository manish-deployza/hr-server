package com.leaveapp.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.PublicHolidayPojo;

public interface PublicHolidayRepo extends JpaRepository<PublicHolidayPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT h FROM PublicHolidayPojo h WHERE h.orgId = :orgId AND h.holidayDate = :holidayDate")
    Optional<PublicHolidayPojo> findByOrgIdAndHolidayDate(
        @Param("orgId") Long orgId, @Param("holidayDate") LocalDate holidayDate);

    @Transactional(readOnly = true)
    @Query("SELECT h FROM PublicHolidayPojo h WHERE h.id = :id AND h.orgId = :orgId")
    Optional<PublicHolidayPojo> findByIdAndOrgId(@Param("id") Long id, @Param("orgId") Long orgId);

    // inclusive both ends -- a year's calendar, or the holidays inside a leave request
    @Transactional(readOnly = true)
    @Query("""
        SELECT h FROM PublicHolidayPojo h
        WHERE h.orgId = :orgId AND h.holidayDate BETWEEN :start AND :end
        ORDER BY h.holidayDate ASC
        """)
    List<PublicHolidayPojo> findByOrgIdAndHolidayDateBetween(
        @Param("orgId") Long orgId, @Param("start") LocalDate start, @Param("end") LocalDate end);
}
