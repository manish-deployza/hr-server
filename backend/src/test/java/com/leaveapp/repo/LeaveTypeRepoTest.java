package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.pojo.LeaveTypePojo;

public class LeaveTypeRepoTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveTypeRepo leaveTypeRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        LeaveTypePojo saved = leaveTypeRepo.save(LeaveTypeTestData.aLeaveType("CL"));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByOrgIdAndCode_returnsMatch() {
        leaveTypeRepo.save(LeaveTypeTestData.aLeaveType("PL"));
        Optional<LeaveTypePojo> found = leaveTypeRepo.findByOrgIdAndCode(1L, "PL");
        assertTrue(found.isPresent());
    }

    @Test
    public void findByOrgIdAndCode_noMatch_empty() {
        assertTrue(leaveTypeRepo.findByOrgIdAndCode(1L, "NOPE").isEmpty());
    }

    @Test
    public void findByOrgIdAndCode_scopedToOrg_noMatchInOtherOrg() {
        leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(1L, "SCOPE"));
        assertTrue(leaveTypeRepo.findByOrgIdAndCode(2L, "SCOPE").isEmpty());
    }

    @Test
    public void findByIdAndOrgId_returnsMatch() {
        LeaveTypePojo saved = leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(1L, "IDORG"));
        assertTrue(leaveTypeRepo.findByIdAndOrgId(saved.getId(), 1L).isPresent());
    }

    @Test
    public void findByIdAndOrgId_wrongOrg_empty() {
        LeaveTypePojo saved = leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(1L, "IDORG2"));
        assertTrue(leaveTypeRepo.findByIdAndOrgId(saved.getId(), 2L).isEmpty());
    }

    @Test
    public void findByOrgIdAndActiveTrue_excludesOtherOrgAndInactive() {
        leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(1L, "ACT1"));
        LeaveTypePojo inactive = LeaveTypeTestData.aLeaveType(1L, "INACT");
        inactive.setActive(false);
        leaveTypeRepo.save(inactive);
        leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(2L, "ACT2"));

        List<LeaveTypePojo> found = leaveTypeRepo.findByOrgIdAndActiveTrue(1L);
        assertEquals(1, found.size());
        assertEquals("ACT1", found.get(0).getCode());
    }

    @Test
    public void findByOrgId_includesInactive_excludesOtherOrg() {
        leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(1L, "ALL1"));
        LeaveTypePojo inactive = LeaveTypeTestData.aLeaveType(1L, "ALL2");
        inactive.setActive(false);
        leaveTypeRepo.save(inactive);
        leaveTypeRepo.save(LeaveTypeTestData.aLeaveType(2L, "OTHERORG"));

        assertEquals(2, leaveTypeRepo.findByOrgId(1L).size());
    }

    @Test
    public void save_nullOrgId_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A0");
        t.setOrgId(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_nullCode_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A1");
        t.setCode(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_nullName_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A2");
        t.setName(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_nullApplicableEmployeeType_succeeds() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A3");
        t.setApplicableType(null); // null = applies to everyone
        assertNotNull(leaveTypeRepo.saveAndFlush(t).getId());
    }

    @Test
    public void save_nullApproverRole_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A3R");
        t.setApproverRole(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_nullAccrualStrategy_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A4");
        t.setAccrualStrategy(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A5");
        t.setCreatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        LeaveTypePojo t = LeaveTypeTestData.aLeaveType("A6");
        t.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> leaveTypeRepo.saveAndFlush(t));
    }

    @Test
    public void save_duplicateCode_throws() {
        leaveTypeRepo.saveAndFlush(LeaveTypeTestData.aLeaveType("DUP"));
        assertThrows(DataIntegrityViolationException.class,
            () -> leaveTypeRepo.saveAndFlush(LeaveTypeTestData.aLeaveType("DUP")));
    }

    // the tenancy fix: uk_leave_type_org_code is scoped by org, not global -- two
    // orgs may each have their own "DUP" code.
    @Test
    public void save_sameCodeDifferentOrg_succeeds() {
        leaveTypeRepo.saveAndFlush(LeaveTypeTestData.aLeaveType(1L, "SHARED"));
        LeaveTypePojo saved = leaveTypeRepo.saveAndFlush(LeaveTypeTestData.aLeaveType(2L, "SHARED"));
        assertNotNull(saved.getId());
    }
}
