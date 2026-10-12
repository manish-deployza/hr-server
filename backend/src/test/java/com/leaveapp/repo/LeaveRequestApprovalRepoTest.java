package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;

public class LeaveRequestApprovalRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveRequestApprovalRepo approvalRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        LeaveRequestApprovalPojo saved = approvalRepo.save(LeaveRequestApprovalTestData.anApproval(1L));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByLeaveRequestId_returnsIt() {
        approvalRepo.save(LeaveRequestApprovalTestData.anApproval(1L));

        List<LeaveRequestApprovalPojo> found = approvalRepo.findByLeaveRequestId(1L);
        assertEquals(1, found.size());
        assertEquals(1L, found.get(0).getLeaveRequestId());
    }

    @Test
    public void save_duplicateLeaveRequestId_throws() {
        approvalRepo.saveAndFlush(LeaveRequestApprovalTestData.anApproval(1L));
        LeaveRequestApprovalPojo dup = LeaveRequestApprovalTestData.anApproval(1L);
        assertThrows(DataIntegrityViolationException.class, () -> approvalRepo.saveAndFlush(dup));
    }

    @Test
    public void save_differentLeaveRequestId_succeeds() {
        approvalRepo.saveAndFlush(LeaveRequestApprovalTestData.anApproval(1L));
        LeaveRequestApprovalPojo saved = approvalRepo.saveAndFlush(LeaveRequestApprovalTestData.anApproval(2L));
        assertNotNull(saved.getId());
    }

    @Test
    public void save_nullLeaveRequestId_throws() {
        LeaveRequestApprovalPojo a = LeaveRequestApprovalTestData.anApproval(1L);
        a.setLeaveRequestId(null);
        assertThrows(DataIntegrityViolationException.class, () -> approvalRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullApproverRole_throws() {
        LeaveRequestApprovalPojo a = LeaveRequestApprovalTestData.anApproval(1L);
        a.setApproverRole(null);
        assertThrows(DataIntegrityViolationException.class, () -> approvalRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullAction_throws() {
        LeaveRequestApprovalPojo a = LeaveRequestApprovalTestData.anApproval(1L);
        a.setAction(null);
        assertThrows(DataIntegrityViolationException.class, () -> approvalRepo.saveAndFlush(a));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        LeaveRequestApprovalPojo a = LeaveRequestApprovalTestData.anApproval(1L);
        a.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> approvalRepo.saveAndFlush(a));
    }
}
