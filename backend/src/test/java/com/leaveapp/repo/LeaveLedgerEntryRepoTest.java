package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.LeaveLedgerEntryPojo;

public class LeaveLedgerEntryRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveLedgerEntryRepo ledgerRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        LeaveLedgerEntryPojo saved = ledgerRepo.save(LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByUserIdAndLeaveTypeIdAndLeaveYear_returnsMatch() {
        ledgerRepo.save(LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026));
        List<LeaveLedgerEntryPojo> found = ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(1L, 1L, 2026);
        assertEquals(1, found.size());
    }

    @Test
    public void findByUserIdAndLeaveTypeIdAndLeaveYear_noMatch_empty() {
        ledgerRepo.save(LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026));
        assertTrue(ledgerRepo.findByUserIdAndLeaveTypeIdAndLeaveYear(1L, 1L, 2025).isEmpty());
    }

    @Test
    public void save_nullUserId_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setUserId(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullLeaveTypeId_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setLeaveTypeId(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullEntryType_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setEntryType(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullAmountDays_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setAmountDays(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullEffectiveDate_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setEffectiveDate(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullCreatedBy_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setCreatedBy(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        LeaveLedgerEntryPojo e = LeaveLedgerEntryTestData.anEntry(1L, 1L, 2026);
        e.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> ledgerRepo.saveAndFlush(e));
    }
}
