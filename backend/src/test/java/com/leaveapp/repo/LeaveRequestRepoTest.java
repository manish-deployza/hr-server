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
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.model.enums.LeaveStatus;

public class LeaveRequestRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveRequestRepo leaveRequestRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        LeaveRequestPojo saved = leaveRequestRepo.save(LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByUserId_returnsOnlyThatUsersRequests() {
        leaveRequestRepo.save(LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER));
        leaveRequestRepo.save(LeaveRequestTestData.aRequest(2L, 1L, LeaveStatus.PENDING_MANAGER));

        List<LeaveRequestPojo> found = leaveRequestRepo.findByUserId(1L);

        assertEquals(1, found.size());
        assertEquals(1L, found.get(0).getUserId());
    }

    @Test
    public void findByUserId_noMatch_empty() {
        assertTrue(leaveRequestRepo.findByUserId(999L).isEmpty());
    }

    @Test
    public void findByUserIdIn_returnsRequestsForListedUsersOnly() {
        leaveRequestRepo.save(LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER));
        leaveRequestRepo.save(LeaveRequestTestData.aRequest(2L, 1L, LeaveStatus.PENDING_MANAGER));
        leaveRequestRepo.save(LeaveRequestTestData.aRequest(3L, 1L, LeaveStatus.PENDING_MANAGER));

        List<LeaveRequestPojo> found = leaveRequestRepo.findByUserIdIn(List.of(1L, 2L));

        assertEquals(2, found.size());
        assertTrue(found.stream().noneMatch(r -> r.getUserId().equals(3L)));
    }

    @Test
    public void findByUserIdIn_emptyCollection_empty() {
        leaveRequestRepo.save(LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER));
        assertTrue(leaveRequestRepo.findByUserIdIn(List.of()).isEmpty());
    }

    @Test
    public void save_nullUserId_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setUserId(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullLeaveTypeId_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setLeaveTypeId(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullStartDate_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setStartDate(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullEndDate_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setEndDate(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullNumDays_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setNumDays(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullStatus_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setStatus(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        LeaveRequestPojo r = LeaveRequestTestData.aRequest(1L, 1L, LeaveStatus.PENDING_MANAGER);
        r.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveRequestRepo.saveAndFlush(r));
    }
}
