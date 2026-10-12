package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// ManagerLeaveFlow -> EmpService      -> EmpRepo         -> DB (direct reports)
// ManagerLeaveFlow -> LeaveBalanceService  -> LeaveLedgerEntryRepo -> DB (per-report balances)
// ManagerLeaveFlow -> LeaveRequestService  -> LeaveRequestRepo     -> DB (their requests)
// ManagerLeaveFlow -> LeaveTypeService     -> LeaveTypeRepo        -> DB (type code/name)
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.ManagerTeamRowData;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.LeaveBalanceService;
import com.leaveapp.service.LeaveRequestService;
import com.leaveapp.service.LeaveTypeService;

// Manager view: their direct reports and those reports' leave requests. Scope is
// "manager_id == caller" -- a manager never sees the whole company. No status
// pre-filter; the screen filters client-side.
@Service
public class ManagerLeaveFlow {

    /* Variables */

    @Autowired
    private EmpService employeeSvc;

    @Autowired
    private LeaveBalanceService balanceSvc;

    @Autowired
    private LeaveRequestService requestSvc;

    @Autowired
    private LeaveTypeService typeSvc;

    /* Public Methods */

    // Writable: currentBalances seeds this year's accrual on first read.
    @Transactional(rollbackFor = Exception.class)
    public List<ManagerTeamRowData> team(Long managerUserId) {
        List<ManagerTeamRowData> team = new ArrayList<>();
        for (EmpPojo report : employeeSvc.findByManager(managerUserId)) {
            ManagerTeamRowData member = new ManagerTeamRowData();
            member.setId(report.getUserId());
            member.setName(report.getName());
            member.setDesignation(report.getDesignation());
            member.setDepartment(report.getDepartment());

            List<LeaveTypePojo> types = typeSvc.listApplicable(report.getOrgId(), report.getEmployeeType());
            Map<Long, BigDecimal> balances = balanceSvc.currentBalances(report.getUserId(), types, report.getDateOfJoining());
            member.setBalances(types.stream().map(t -> LeaveMapper.toBalanceData(t, balances.get(t.getId()))).toList());
            team.add(member);
        }
        return team;
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveRequestData> teamRequests(Long managerUserId) {
        Long orgId = employeeSvc.findByUserId(managerUserId).getOrgId();
        Map<Long, EmpPojo> reports = employeeSvc.findByManager(managerUserId).stream()
            .collect(Collectors.toMap(EmpPojo::getUserId, Function.identity()));
        Map<Long, LeaveTypePojo> types = typeSvc.listAll(orgId).stream()
            .collect(Collectors.toMap(LeaveTypePojo::getId, Function.identity()));
        return requestSvc.findByUsers(reports.keySet()).stream()
            .map(r -> LeaveMapper.toSummary(r, reports.get(r.getUserId()), types.get(r.getLeaveTypeId())))
            .toList();
    }
}
