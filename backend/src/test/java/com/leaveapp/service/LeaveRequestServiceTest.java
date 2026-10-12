package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;
import com.leaveapp.pojo.LeaveRequestPojo;

// Thin persistence wrapper: lookups (found / missing / null id), per-user and multi-user
// listings (incl. the empty-collection short-circuit) and approval rows.
public class LeaveRequestServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveRequestService requestSvc;

    /* Public Methods */

    @Test
    public void findById_existing_returned_missing_notFound() {
        LeaveRequestPojo saved = requestSvc.save(request(1L));

        assertEquals(saved.getId(), requestSvc.findById(saved.getId()).getId());
        assertThrows(NotFoundException.class, () -> requestSvc.findById(-1L));
    }

    @Test
    public void findByIdOrNull_nullIdMissingAndExisting() {
        LeaveRequestPojo saved = requestSvc.save(request(1L));

        assertNull(requestSvc.findByIdOrNull(null));
        assertNull(requestSvc.findByIdOrNull(-1L));
        assertEquals(saved.getId(), requestSvc.findByIdOrNull(saved.getId()).getId());
    }

    @Test
    public void findByUser_andFindByUsers_scopeByUserIds() {
        requestSvc.save(request(101L));
        requestSvc.save(request(101L));
        requestSvc.save(request(102L));
        requestSvc.save(request(103L));

        assertEquals(2, requestSvc.findByUser(101L).size());
        assertEquals(3, requestSvc.findByUsers(Set.of(101L, 102L)).size());
        assertEquals(0, requestSvc.findByUser(999L).size());
    }

    @Test
    public void findByUsers_emptyCollection_returnsEmptyWithoutQuery() {
        assertTrue(requestSvc.findByUsers(List.of()).isEmpty());
    }

    @Test
    public void saveApproval_andApprovalsOf_roundTrip() {
        LeaveRequestPojo req = requestSvc.save(request(1L));
        LeaveRequestApprovalPojo approval = new LeaveRequestApprovalPojo();
        approval.setLeaveRequestId(req.getId());
        approval.setApproverRole(ApproverRole.HR);
        approval.setAction(ApprovalAction.PENDING);
        approval.setCreatedAt(Instant.now());

        LeaveRequestApprovalPojo saved = requestSvc.saveApproval(approval);

        assertNotNull(saved.getId());
        assertEquals(1, requestSvc.approvalsOf(req.getId()).size());
        assertEquals(ApproverRole.HR, requestSvc.approvalsOf(req.getId()).get(0).getApproverRole());
        assertTrue(requestSvc.approvalsOf(-1L).isEmpty());
    }

    /* Private Helper Methods */

    private static LeaveRequestPojo request(Long userId) {
        Instant now = Instant.now();
        LeaveRequestPojo r = new LeaveRequestPojo();
        r.setUserId(userId);
        r.setLeaveTypeId(1L);
        r.setStartDate(LocalDate.now().plusDays(10));
        r.setEndDate(LocalDate.now().plusDays(10));
        r.setNumDays(new BigDecimal("1.0"));
        r.setHrImposed(false);
        r.setStatus(LeaveStatus.APPLIED);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }
}
