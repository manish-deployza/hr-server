package com.leaveapp.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.PublicHolidayPojo;

@Transactional
public interface PublicHolidayRepo extends JpaRepository<PublicHolidayPojo, Long> {

    Optional<PublicHolidayPojo> findByOrganizationIdAndHolidayDate(Long organizationId, LocalDate holidayDate);

    List<PublicHolidayPojo> findByOrganizationIdAndYear(Long organizationId, int year);

    // inclusive both ends -- used to subtract holidays from a leave-request day count
    List<PublicHolidayPojo> findByOrganizationIdAndHolidayDateBetween(Long organizationId, LocalDate start, LocalDate end);
}
