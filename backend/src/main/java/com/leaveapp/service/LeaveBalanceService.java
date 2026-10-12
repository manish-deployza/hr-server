package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB):
// LeaveBalanceService -> LeaveLedgerEntryRepo -> DB (append-only ledger rows)
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.enums.LedgerEntryType;
import com.leaveapp.pojo.LeaveLedgerEntryPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.repo.LeaveLedgerEntryRepo;

// The leave ledger is append-only: a balance is always SUM(amount_days) for a
// user + type + calendar year. This service reads that sum and writes the two
// entry kinds the leave flow produces -- the yearly ACCRUAL credit and a DEBIT
// when a request is fully approved.
@Service
public class LeaveBalanceService {

    /* Variables */

    private static final int MONTHS_PER_YEAR = 12;

    @Autowired
    private LeaveLedgerEntryRepo ledgerRepo;

    /* Public Methods */

    // Current-year balance for every given leave type, keyed by leave type id, seeding
    // the annual accrual first. Shared by GET /leave-balances/me and the per-report
    // balances embedded in GET /manager/team -- both flows resolve the applicable
    // types themselves and assemble the *Data shape; this returns primitives only.
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, BigDecimal> currentBalances(Long userId, List<LeaveTypePojo> types, LocalDate dateOfJoining) {
        int year = Year.now().getValue();
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (LeaveTypePojo type : types) {
            ensureAnnualAccrual(userId, type, year, dateOfJoining);
            balances.put(type.getId(), balance(userId, type.getId(), year));
        }
        return balances;
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public BigDecimal balance(Long userId, Long leaveTypeId, int year) {
        return ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(userId, leaveTypeId, year).stream()
            .map(LeaveLedgerEntryPojo::getAmountDays)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Credit this year's entitlement once, prorated by joining month. Idempotent --
    // a second call in the same year finds the existing ACCRUAL row and does nothing.
    // ponytail: naive -- one annual lump sum, no quarterly PL cadence and no
    // carry-forward cap; a scheduled accrual job replaces this once the leave-policy
    // engine exists.
    @Transactional(rollbackFor = Exception.class)
    public void ensureAnnualAccrual(Long userId, LeaveTypePojo type, int year, LocalDate dateOfJoining) {
        BigDecimal entitlement = type.getDaysPerYear();
        // no joining date yet (HR hasn't set one) = not accrued yet; the first call
        // after HR sets it credits the year from the real date.
        if (dateOfJoining == null || entitlement == null || entitlement.signum() <= 0) {
            return;
        }
        List<LeaveLedgerEntryPojo> existing =
            ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(userId, type.getId(), year);
        if (existing.stream().anyMatch(e -> LedgerEntryType.ACCRUAL == e.getEntryType())) {
            return;
        }
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        boolean joinedThisYear = dateOfJoining.getYear() == year;
        int monthsRemaining = joinedThisYear
            ? MONTHS_PER_YEAR - dateOfJoining.getMonthValue() + 1
            : MONTHS_PER_YEAR;
        BigDecimal credit = roundToHalfDay(entitlement
            .multiply(BigDecimal.valueOf(monthsRemaining))
            .divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 4, RoundingMode.HALF_UP));

        LeaveLedgerEntryPojo entry = newEntry(userId, type.getId(), year, LedgerEntryType.ACCRUAL, credit, userId);
        entry.setEffectiveDate(joinedThisYear ? dateOfJoining : yearStart);
        ledgerRepo.save(entry);
    }

    // DEBIT written when a request reaches APPROVED. `actorId` is whoever gave the
    // final approval (schema has no nullable job-name column, so an id is required).
    @Transactional(rollbackFor = Exception.class)
    public void recordConsumption(Long userId, Long leaveTypeId, LocalDate startDate,
        BigDecimal days, Long requestId, Long actorId) {
        LeaveLedgerEntryPojo entry =
            newEntry(userId, leaveTypeId, startDate.getYear(), LedgerEntryType.DEBIT, days.negate(), actorId);
        entry.setEffectiveDate(startDate);
        entry.setRefRequestId(requestId);
        ledgerRepo.save(entry);
    }

    /* Private Helper Methods */

    private static LeaveLedgerEntryPojo newEntry(Long userId, Long leaveTypeId, int year,
        LedgerEntryType entryType, BigDecimal amountDays, Long createdBy) {
        LeaveLedgerEntryPojo entry = new LeaveLedgerEntryPojo();
        entry.setUserId(userId);
        entry.setLeaveTypeId(leaveTypeId);
        entry.setLeaveYear(year);
        entry.setEntryType(entryType);
        entry.setAmountDays(amountDays.setScale(1, RoundingMode.HALF_UP));
        entry.setCreatedBy(createdBy);
        entry.setCreatedAt(Instant.now());
        return entry;
    }

    // Leave is granted in half-day units.
    private static BigDecimal roundToHalfDay(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(2))
            .setScale(0, RoundingMode.HALF_UP)
            .divide(BigDecimal.valueOf(2))
            .setScale(1, RoundingMode.HALF_UP);
    }
}
