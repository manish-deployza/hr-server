package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.HolidayData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.HolidayForm;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.model.form.LeaveTypeForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.LeaveTypeRepo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

// HR surface: company-wide requests, leave-type catalog CRUD (incl. the days-per-year
// label parsing) and the holiday calendar, all scoped to the caller's org.
public class HrLeaveFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private HrLeaveFlow hrLeaveFlow;

    @Autowired
    private LeaveFlow leaveFlow;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private LeaveTypeRepo typeRepo;

    /* Public Methods */

    @Test
    public void allRequests_returnsOrgRequests_notOtherOrgs() {
        Long orgId = orgSvc.create("Acme", "hr-flow-1", "hr@hr-flow-1.test", "IN").getId();
        Long otherOrg = orgSvc.create("Other", "hr-flow-1b", "hr@hr-flow-1b.test", "IN").getId();
        UserPojo emp = user(orgId, "e@hr-flow-1.test");
        UserPojo otherEmp = user(otherOrg, "e@hr-flow-1b.test");
        Long typeId = leaveType(orgId, "CL").getId();
        Long otherTypeId = leaveType(otherOrg, "CL").getId();
        leaveFlow.apply(emp.getId(), applyForm(typeId, nextMonday()));
        leaveFlow.apply(otherEmp.getId(), applyForm(otherTypeId, nextMonday()));

        List<LeaveRequestData> rows = hrLeaveFlow.allRequests(orgId);

        assertEquals(1, rows.size());
        assertEquals(emp.getId(), rows.get(0).getEmployeeId());
        assertEquals("CL", rows.get(0).getLeaveTypeCode());
    }

    @Test
    public void allRequests_orgWithNoRequests_isEmpty() {
        Long orgId = orgSvc.create("Acme", "hr-flow-2", "hr@hr-flow-2.test", "IN").getId();

        assertTrue(hrLeaveFlow.allRequests(orgId).isEmpty());
    }

    @Test
    public void createLeaveType_parsesLeadingNumberFromLabel() {
        Long orgId = orgSvc.create("Acme", "hr-flow-3", "hr@hr-flow-3.test", "IN").getId();

        Long labelled = hrLeaveFlow.createLeaveType(orgId, typeForm("Casual", "14 days/year")).getId();
        Long plain = hrLeaveFlow.createLeaveType(orgId, typeForm("Plain", "7.5")).getId();

        assertEquals(0, new BigDecimal("14").compareTo(typeRepo.findById(labelled).orElseThrow().getDaysPerYear()));
        assertEquals(0, new BigDecimal("7.5").compareTo(typeRepo.findById(plain).orElseThrow().getDaysPerYear()));
    }

    @Test
    public void createLeaveType_nullOrNonNumericDays_meansNoEntitlement() {
        Long orgId = orgSvc.create("Acme", "hr-flow-4", "hr@hr-flow-4.test", "IN").getId();

        Long nullDays = hrLeaveFlow.createLeaveType(orgId, typeForm("Unpaid", null)).getId();
        Long wordDays = hrLeaveFlow.createLeaveType(orgId, typeForm("Sabbatical", "as needed")).getId();

        assertNull(typeRepo.findById(nullDays).orElseThrow().getDaysPerYear());
        assertNull(typeRepo.findById(wordDays).orElseThrow().getDaysPerYear());
    }

    @Test
    public void updateLeaveType_changesFields_andListShowsThem() {
        Long orgId = orgSvc.create("Acme", "hr-flow-5", "hr@hr-flow-5.test", "IN").getId();
        Long id = hrLeaveFlow.createLeaveType(orgId, typeForm("Casual", "10")).getId();
        LeaveTypeForm patch = typeForm("Casual Plus", "12 days/year");
        patch.setCode("CAS_PLUS");
        patch.setInfoNote("updated");

        hrLeaveFlow.updateLeaveType(orgId, id, patch);

        LeaveTypeData shown = hrLeaveFlow.leaveTypes(orgId).stream()
            .filter(t -> t.getId().equals(id)).findFirst().orElseThrow();
        assertEquals("Casual Plus", shown.getName());
        assertEquals("CAS_PLUS", shown.getCode());
        assertEquals("12 days/year", shown.getDaysPerYear());
        assertEquals("updated", shown.getInfoNote());
    }

    @Test
    public void updateLeaveType_otherOrg_notFound() {
        Long orgId = orgSvc.create("Acme", "hr-flow-6", "hr@hr-flow-6.test", "IN").getId();
        Long otherOrg = orgSvc.create("Other", "hr-flow-6b", "hr@hr-flow-6b.test", "IN").getId();
        Long id = hrLeaveFlow.createLeaveType(orgId, typeForm("Casual", "10")).getId();

        assertThrows(NotFoundException.class, () -> hrLeaveFlow.updateLeaveType(otherOrg, id, typeForm("X", null)));
    }

    @Test
    public void deleteLeaveType_dropsOutOfActiveList() {
        Long orgId = orgSvc.create("Acme", "hr-flow-7", "hr@hr-flow-7.test", "IN").getId();
        Long id = hrLeaveFlow.createLeaveType(orgId, typeForm("Casual", "10")).getId();

        hrLeaveFlow.deleteLeaveType(orgId, id);

        assertTrue(hrLeaveFlow.leaveTypes(orgId).stream().noneMatch(t -> t.getId().equals(id)));
    }

    @Test
    public void holidays_defaultsToCurrentYear_whenYearNull() {
        Long orgId = orgSvc.create("Acme", "hr-flow-8", "hr@hr-flow-8.test", "IN").getId();
        int thisYear = Year.now().getValue();
        hrLeaveFlow.createHoliday(orgId, holidayForm("New Year", LocalDate.of(thisYear, 1, 1)));
        hrLeaveFlow.createHoliday(orgId, holidayForm("Old", LocalDate.of(thisYear - 1, 1, 1)));

        List<HolidayData> current = hrLeaveFlow.holidays(orgId, null);
        List<HolidayData> previous = hrLeaveFlow.holidays(orgId, thisYear - 1);

        assertEquals(1, current.size());
        assertEquals("New Year", current.get(0).getName());
        assertEquals(thisYear, current.get(0).getYear());
        assertEquals(LocalDate.of(thisYear, 1, 1), current.get(0).getDate());
        assertEquals(1, previous.size());
        assertEquals("Old", previous.get(0).getName());
    }

    @Test
    public void updateAndDeleteHoliday_roundTrip() {
        Long orgId = orgSvc.create("Acme", "hr-flow-9", "hr@hr-flow-9.test", "IN").getId();
        int thisYear = Year.now().getValue();
        Long id = hrLeaveFlow.createHoliday(orgId, holidayForm("Diwali", LocalDate.of(thisYear, 11, 1))).getId();

        hrLeaveFlow.updateHoliday(orgId, id, holidayForm("Diwali Day", LocalDate.of(thisYear, 11, 2)));

        HolidayData updated = hrLeaveFlow.holidays(orgId, thisYear).get(0);
        assertEquals("Diwali Day", updated.getName());
        assertEquals(LocalDate.of(thisYear, 11, 2), updated.getDate());

        hrLeaveFlow.deleteHoliday(orgId, id);

        assertTrue(hrLeaveFlow.holidays(orgId, thisYear).isEmpty());
    }

    @Test
    public void deleteHoliday_otherOrg_notFound() {
        Long orgId = orgSvc.create("Acme", "hr-flow-10", "hr@hr-flow-10.test", "IN").getId();
        Long otherOrg = orgSvc.create("Other", "hr-flow-10b", "hr@hr-flow-10b.test", "IN").getId();
        Long id = hrLeaveFlow.createHoliday(orgId, holidayForm("Fest", LocalDate.of(2031, 5, 5))).getId();

        assertThrows(NotFoundException.class, () -> hrLeaveFlow.deleteHoliday(otherOrg, id));
    }

    /* Private Helper Methods */

    private UserPojo user(Long orgId, String email) {
        UserPojo u = userSvc.createUser(orgId, email, "s3cretPass!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE);
        EmpPojo emp = new EmpPojo();
        emp.setStatus(EmpStatus.ACTIVE);
        emp.setUserId(u.getId());
        emp.setOrgId(orgId);
        emp.setEmployeeCode("E-" + u.getId());
        emp.setName("Emp " + u.getId());
        emp.setDateOfJoining(LocalDate.now().minusYears(2));
        emp.setEmployeeType("REGULAR");
        empSvc.createProfile(emp);
        return u;
    }

    private LeaveTypePojo leaveType(Long orgId, String code) {
        Instant now = Instant.now();
        LeaveTypePojo t = new LeaveTypePojo();
        t.setOrgId(orgId);
        t.setCode(code);
        t.setName(code + " Leave");
        t.setApproverRole(ApproverRole.MANAGER);
        t.setAccrualStrategy(AccrualStrategy.ANNUAL_PRORATA);
        t.setEncashable(false);
        t.setActive(true);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return typeRepo.save(t);
    }

    private static LeaveTypeForm typeForm(String name, String daysPerYear) {
        LeaveTypeForm form = new LeaveTypeForm();
        form.setName(name);
        form.setDaysPerYear(daysPerYear);
        return form;
    }

    private static HolidayForm holidayForm(String name, LocalDate date) {
        HolidayForm form = new HolidayForm();
        form.setName(name);
        form.setDate(date);
        return form;
    }

    private static LeaveApplyForm applyForm(Long leaveTypeId, LocalDate day) {
        LeaveApplyForm form = new LeaveApplyForm();
        form.setLeaveTypeId(leaveTypeId);
        form.setStartDate(day);
        form.setEndDate(day);
        form.setReason("test");
        return form;
    }

    private static LocalDate nextMonday() {
        LocalDate d = LocalDate.now().plusDays(14);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }
}
