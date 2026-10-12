package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.data.LeaveBalanceData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.enums.ApprovalAction;
import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.LeaveStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveRequestApprovalPojo;
import com.leaveapp.pojo.LeaveRequestPojo;
import com.leaveapp.pojo.LeaveTypePojo;

// Pure field-copy mapping: every null-guard and flag arm of LeaveMapper.
public class LeaveMapperTest {

    /* Variables */

    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2030-01-02T00:00:00Z");

    /* Public Methods */

    @Test
    public void toTypeData_copiesFieldsAndFormatsDaysPerYear() {
        LeaveTypePojo type = type();
        type.setNoticeDays(7);
        type.setInfoNote("note");

        LeaveTypeData data = LeaveMapper.toTypeData(type);

        assertEquals(5L, data.getId());
        assertEquals("CL", data.getCode());
        assertEquals("Casual", data.getName());
        assertEquals("12 days/year", data.getDaysPerYear());
        assertEquals(7, data.getRequiresAdvanceNoticeDays());
        assertEquals("note", data.getInfoNote());
    }

    @Test
    public void toBalanceData_copiesTypeAndBalance() {
        LeaveBalanceData data = LeaveMapper.toBalanceData(type(), new BigDecimal("3.5"));

        assertEquals(5L, data.getLeaveTypeId());
        assertEquals("CL", data.getLeaveTypeCode());
        assertEquals("Casual", data.getLeaveTypeName());
        assertEquals(new BigDecimal("3.5"), data.getBalance());
    }

    @Test
    public void toSummary_notHrImposed_leavesFlagUnset() {
        LeaveRequestData data = LeaveMapper.toSummary(request(LeaveStatus.APPROVED, false), applicant(), type());

        assertEquals("Asha", data.getEmployeeName());
        assertEquals("CL", data.getLeaveTypeCode());
        assertEquals("Casual", data.getLeaveTypeName());
        assertEquals(LeaveStatus.APPROVED, data.getStatus());
        assertEquals(CREATED, data.getAppliedAt());
        assertNull(data.getIsHrImposed());
    }

    @Test
    public void toSummary_hrImposed_setsFlag() {
        LeaveRequestData data = LeaveMapper.toSummary(request(LeaveStatus.APPROVED, true), applicant(), type());

        assertEquals(Boolean.TRUE, data.getIsHrImposed());
    }

    @Test
    public void toSummary_missingApplicantAndType_leavesNamesNull() {
        LeaveRequestData data = LeaveMapper.toSummary(request(LeaveStatus.APPROVED, false), null, null);

        assertNull(data.getEmployeeName());
        assertNull(data.getLeaveTypeCode());
        assertNull(data.getLeaveTypeName());
        assertEquals(9L, data.getEmployeeId());
        assertEquals(5L, data.getLeaveTypeId());
    }

    @Test
    public void toDetail_cancelled_exposesCancelledAtAndHrFlag() {
        LeaveRequestData data = LeaveMapper.toDetail(request(LeaveStatus.CANCELLED, true), applicant(), type(),
            List.of(approval()));

        assertEquals(UPDATED, data.getCancelledAt());
        assertEquals(Boolean.TRUE, data.getIsHrImposed());
        assertEquals("why", data.getReason());
        assertEquals("hr note", data.getHrNotes());
        assertEquals(1, data.getApprovals().size());
        LeaveRequestData.ApprovalData a = data.getApprovals().get(0);
        assertEquals(ApproverRole.MANAGER, a.getApproverRole());
        assertEquals(3L, a.getApproverEmployeeId());
        assertEquals(ApprovalAction.APPROVED, a.getAction());
        assertEquals("ok", a.getComments());
        assertEquals(UPDATED, a.getActedAt());
    }

    @Test
    public void toDetail_notCancelledNotHrImposed_leavesBothNull() {
        LeaveRequestData data = LeaveMapper.toDetail(request(LeaveStatus.APPROVED, false), applicant(), type(),
            List.of());

        assertNull(data.getCancelledAt());
        assertNull(data.getIsHrImposed());
        assertEquals(0, data.getApprovals().size());
    }

    /* Private Helper Methods */

    private static LeaveTypePojo type() {
        LeaveTypePojo t = new LeaveTypePojo();
        t.setId(5L);
        t.setCode("CL");
        t.setName("Casual");
        t.setDaysPerYear(new BigDecimal("12.00"));
        return t;
    }

    private static EmpPojo applicant() {
        EmpPojo e = new EmpPojo();
        e.setStatus(EmpStatus.ACTIVE);
        e.setName("Asha");
        return e;
    }

    private static LeaveRequestPojo request(LeaveStatus status, boolean hrImposed) {
        LeaveRequestPojo r = new LeaveRequestPojo();
        r.setId(1L);
        r.setUserId(9L);
        r.setLeaveTypeId(5L);
        r.setStartDate(LocalDate.of(2030, 3, 4));
        r.setEndDate(LocalDate.of(2030, 3, 5));
        r.setNumDays(new BigDecimal("2.0"));
        r.setStatus(status);
        r.setHrImposed(hrImposed);
        r.setReason("why");
        r.setHrNotes("hr note");
        r.setCreatedAt(CREATED);
        r.setUpdatedAt(UPDATED);
        return r;
    }

    private static LeaveRequestApprovalPojo approval() {
        LeaveRequestApprovalPojo a = new LeaveRequestApprovalPojo();
        a.setApproverRole(ApproverRole.MANAGER);
        a.setApproverUserId(3L);
        a.setAction(ApprovalAction.APPROVED);
        a.setComments("ok");
        a.setActedAt(UPDATED);
        return a;
    }
}
