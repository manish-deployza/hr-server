package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.ManagerTeamRowData;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.LeaveApplyForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.repo.LeaveTypeRepo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.UserService;

// Manager view: direct reports + their balances, and the reports' requests only.
public class ManagerLeaveFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private ManagerLeaveFlow managerFlow;

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
    public void team_listsDirectReportsWithBalances_notOtherPeople() {
        Long orgId = orgSvc.create("Acme", "mgr-flow-1", "hr@mgr-flow-1.test", "IN").getId();
        UserPojo manager = user(orgId, "m@mgr-flow-1.test");
        employee(manager, null);
        UserPojo report = user(orgId, "r@mgr-flow-1.test");
        employee(report, manager.getId());
        UserPojo stranger = user(orgId, "s@mgr-flow-1.test");
        employee(stranger, null);
        leaveType(orgId, "CL", new BigDecimal("12"));

        List<ManagerTeamRowData> team = managerFlow.team(manager.getId());

        assertEquals(1, team.size());
        assertEquals(report.getId(), team.get(0).getId());
        assertEquals("Emp " + report.getId(), team.get(0).getName());
        assertEquals(1, team.get(0).getBalances().size());
        assertEquals(0, new BigDecimal("12.0").compareTo(team.get(0).getBalances().get(0).getBalance()));
    }

    @Test
    public void team_withoutReports_isEmpty() {
        Long orgId = orgSvc.create("Acme", "mgr-flow-2", "hr@mgr-flow-2.test", "IN").getId();
        UserPojo manager = user(orgId, "m@mgr-flow-2.test");
        employee(manager, null);

        assertTrue(managerFlow.team(manager.getId()).isEmpty());
    }

    @Test
    public void teamRequests_returnsOnlyReportsRequests() {
        Long orgId = orgSvc.create("Acme", "mgr-flow-3", "hr@mgr-flow-3.test", "IN").getId();
        UserPojo manager = user(orgId, "m@mgr-flow-3.test");
        employee(manager, null);
        UserPojo report = user(orgId, "r@mgr-flow-3.test");
        employee(report, manager.getId());
        UserPojo stranger = user(orgId, "s@mgr-flow-3.test");
        employee(stranger, manager.getId() + 1000);
        Long typeId = leaveType(orgId, "CL", new BigDecimal("12")).getId();
        leaveFlow.apply(report.getId(), applyForm(typeId, nextMonday()));
        leaveFlow.apply(stranger.getId(), applyForm(typeId, nextMonday()));

        List<LeaveRequestData> rows = managerFlow.teamRequests(manager.getId());

        assertEquals(1, rows.size());
        assertEquals(report.getId(), rows.get(0).getEmployeeId());
        assertEquals("CL", rows.get(0).getLeaveTypeCode());
    }

    @Test
    public void teamRequests_withoutReports_isEmpty() {
        Long orgId = orgSvc.create("Acme", "mgr-flow-4", "hr@mgr-flow-4.test", "IN").getId();
        UserPojo manager = user(orgId, "m@mgr-flow-4.test");
        employee(manager, null);

        assertTrue(managerFlow.teamRequests(manager.getId()).isEmpty());
    }

    /* Private Helper Methods */

    private UserPojo user(Long orgId, String email) {
        return userSvc.createUser(orgId, email, "s3cretPass!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE);
    }

    private void employee(UserPojo user, Long managerUserId) {
        EmpPojo emp = new EmpPojo();
        emp.setStatus(EmpStatus.ACTIVE);
        emp.setUserId(user.getId());
        emp.setOrgId(user.getOrgId());
        emp.setEmployeeCode("E-" + user.getId());
        emp.setName("Emp " + user.getId());
        emp.setDateOfJoining(LocalDate.now().minusYears(2));
        emp.setEmployeeType("REGULAR");
        emp.setManagerId(managerUserId);
        empSvc.createProfile(emp);
    }

    private LeaveTypePojo leaveType(Long orgId, String code, BigDecimal daysPerYear) {
        Instant now = Instant.now();
        LeaveTypePojo t = new LeaveTypePojo();
        t.setOrgId(orgId);
        t.setCode(code);
        t.setName(code + " Leave");
        t.setApproverRole(ApproverRole.MANAGER);
        t.setAccrualStrategy(AccrualStrategy.ANNUAL_PRORATA);
        t.setEncashable(false);
        t.setActive(true);
        t.setDaysPerYear(daysPerYear);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return typeRepo.save(t);
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
