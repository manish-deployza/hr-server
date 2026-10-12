package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.LedgerEntryType;
import com.leaveapp.pojo.LeaveLedgerEntryPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.repo.LeaveLedgerEntryRepo;

// Ledger-backed balances: yearly accrual (skip rules, idempotency, joining-month proration,
// half-day rounding), DEBIT on consumption, and per-type current balances.
public class LeaveBalanceServiceTest extends RepositoryTestSupport {

    /* Variables */

    private static final int YEAR = Year.now().getValue();

    @Autowired
    private LeaveBalanceService balanceSvc;

    @Autowired
    private LeaveLedgerEntryRepo ledgerRepo;

    /* Public Methods */

    @Test
    public void balance_withNoEntries_isZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceSvc.balance(9001L, 9002L, YEAR)));
    }

    @Test
    public void ensureAnnualAccrual_nullOrNonPositiveEntitlement_writesNothing() {
        balanceSvc.ensureAnnualAccrual(1L, type(10L, null), YEAR, null);
        balanceSvc.ensureAnnualAccrual(1L, type(11L, BigDecimal.ZERO), YEAR, null);
        balanceSvc.ensureAnnualAccrual(1L, type(12L, new BigDecimal("-3")), YEAR, null);

        assertTrue(ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(1L, 10L, YEAR).isEmpty());
        assertTrue(ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(1L, 11L, YEAR).isEmpty());
        assertTrue(ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(1L, 12L, YEAR).isEmpty());
    }

    // no joining date yet = not accrued yet (no placeholder date to prorate from)
    @Test
    public void ensureAnnualAccrual_nullJoiningDate_writesNothing() {
        balanceSvc.ensureAnnualAccrual(2L, type(20L, new BigDecimal("12")), YEAR, null);

        assertTrue(ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(2L, 20L, YEAR).isEmpty());
    }

    @Test
    public void ensureAnnualAccrual_isIdempotent() {
        LeaveTypePojo t = type(21L, new BigDecimal("12"));
        LocalDate joined = LocalDate.of(YEAR - 1, 1, 1);

        balanceSvc.ensureAnnualAccrual(2L, t, YEAR, joined);
        balanceSvc.ensureAnnualAccrual(2L, t, YEAR, joined);

        List<LeaveLedgerEntryPojo> rows = ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(2L, 21L, YEAR);
        assertEquals(1, rows.size());
        assertEquals(LedgerEntryType.ACCRUAL, rows.get(0).getEntryType());
        assertEquals(0, new BigDecimal("12.0").compareTo(rows.get(0).getAmountDays()));
        assertEquals(LocalDate.of(YEAR, 1, 1), rows.get(0).getEffectiveDate());
    }

    @Test
    public void ensureAnnualAccrual_joinedInEarlierYear_creditsFullYear() {
        balanceSvc.ensureAnnualAccrual(3L, type(30L, new BigDecimal("12")), YEAR, LocalDate.of(YEAR - 1, 11, 20));

        assertEquals(0, new BigDecimal("12.0").compareTo(balanceSvc.balance(3L, 30L, YEAR)));
    }

    @Test
    public void ensureAnnualAccrual_joinedThisYear_prorataByJoiningMonth_effectiveOnJoiningDate() {
        LocalDate joined = LocalDate.of(YEAR, 7, 15); // July -> 6 months remaining

        balanceSvc.ensureAnnualAccrual(4L, type(40L, new BigDecimal("12")), YEAR, joined);

        List<LeaveLedgerEntryPojo> rows = ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(4L, 40L, YEAR);
        assertEquals(0, new BigDecimal("6.0").compareTo(rows.get(0).getAmountDays()));
        assertEquals(joined, rows.get(0).getEffectiveDate());
    }

    @Test
    public void ensureAnnualAccrual_proratedCredit_roundsToHalfDay() {
        // 10 days * 5/12 = 4.1667 -> 4.0 ; 10 * 8/12 = 6.667 -> 6.5 ; 10 * 1/12 = 0.833 -> 1.0
        balanceSvc.ensureAnnualAccrual(5L, type(50L, new BigDecimal("10")), YEAR, LocalDate.of(YEAR, 8, 1));
        balanceSvc.ensureAnnualAccrual(5L, type(51L, new BigDecimal("10")), YEAR, LocalDate.of(YEAR, 5, 1));
        balanceSvc.ensureAnnualAccrual(5L, type(52L, new BigDecimal("10")), YEAR, LocalDate.of(YEAR, 12, 31));

        assertEquals(0, new BigDecimal("4.0").compareTo(balanceSvc.balance(5L, 50L, YEAR)));
        assertEquals(0, new BigDecimal("6.5").compareTo(balanceSvc.balance(5L, 51L, YEAR)));
        assertEquals(0, new BigDecimal("1.0").compareTo(balanceSvc.balance(5L, 52L, YEAR)));
    }

    @Test
    public void ensureAnnualAccrual_existingDebitOnly_stillWritesAccrual() {
        LeaveTypePojo t = type(60L, new BigDecimal("12"));
        balanceSvc.recordConsumption(6L, 60L, LocalDate.of(YEAR, 3, 3), new BigDecimal("1"), 77L, 6L);

        balanceSvc.ensureAnnualAccrual(6L, t, YEAR, LocalDate.of(YEAR - 1, 1, 1));

        assertEquals(0, new BigDecimal("11.0").compareTo(balanceSvc.balance(6L, 60L, YEAR)));
    }

    @Test
    public void recordConsumption_writesNegativeDebitInStartDatesYear() {
        balanceSvc.recordConsumption(7L, 70L, LocalDate.of(YEAR + 1, 1, 5), new BigDecimal("2.5"), 99L, 8L);

        LeaveLedgerEntryPojo row = ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(7L, 70L, YEAR + 1).get(0);
        assertEquals(LedgerEntryType.DEBIT, row.getEntryType());
        assertEquals(0, new BigDecimal("-2.5").compareTo(row.getAmountDays()));
        assertEquals(99L, row.getRefRequestId());
        assertEquals(8L, row.getCreatedBy());
        assertEquals(LocalDate.of(YEAR + 1, 1, 5), row.getEffectiveDate());
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceSvc.balance(7L, 70L, YEAR))); // other year untouched
    }

    @Test
    public void currentBalances_seedsAccrualPerType_keyedByTypeId() {
        LeaveTypePojo withDays = type(80L, new BigDecimal("12"));
        LeaveTypePojo noDays = type(81L, null);

        Map<Long, BigDecimal> balances = balanceSvc.currentBalances(8L, List.of(withDays, noDays), LocalDate.of(YEAR - 1, 1, 1));

        assertEquals(2, balances.size());
        assertEquals(0, new BigDecimal("12.0").compareTo(balances.get(80L)));
        assertEquals(0, BigDecimal.ZERO.compareTo(balances.get(81L)));
    }

    @Test
    public void currentBalances_noTypes_isEmpty() {
        assertTrue(balanceSvc.currentBalances(9L, List.of(), null).isEmpty());
    }

    /* Private Helper Methods */

    // In-memory type: the ledger keys on plain ids, so no leave_type row is needed.
    private static LeaveTypePojo type(Long id, BigDecimal daysPerYear) {
        LeaveTypePojo t = new LeaveTypePojo();
        t.setId(id);
        t.setDaysPerYear(daysPerYear);
        return t;
    }
}
