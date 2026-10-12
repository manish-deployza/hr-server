package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// HrLeaveFlow -> UserService          -> UserRepo             -> DB (org member ids)
// HrLeaveFlow -> EmpService      -> EmpRepo         -> DB (applicant names)
// HrLeaveFlow -> LeaveRequestService  -> LeaveRequestRepo     -> DB (company-wide requests)
// HrLeaveFlow -> LeaveTypeService     -> LeaveTypeRepo        -> DB (catalog CRUD)
// HrLeaveFlow -> PublicHolidayService -> PublicHolidayRepo    -> DB (holiday calendar)
import java.math.BigDecimal;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.model.data.HolidayData;
import com.leaveapp.model.data.IdData;
import com.leaveapp.model.data.LeaveRequestData;
import com.leaveapp.model.data.LeaveTypeData;
import com.leaveapp.model.form.HolidayForm;
import com.leaveapp.model.form.LeaveTypeForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.LeaveTypePojo;
import com.leaveapp.pojo.PublicHolidayPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.LeaveRequestService;
import com.leaveapp.service.LeaveTypeService;
import com.leaveapp.service.PublicHolidayService;
import com.leaveapp.service.UserService;

// HR / org-admin leave surface: the company-wide request view, the leave-type
// catalog CRUD, and the holiday calendar. Scoped to the caller's own org via the
// JWT org id -- no org parameter crosses the wire.
@Service
public class HrLeaveFlow {

    /* Variables */

    private static final Pattern LEADING_NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService employeeSvc;

    @Autowired
    private LeaveRequestService requestSvc;

    @Autowired
    private LeaveTypeService typeSvc;

    @Autowired
    private PublicHolidayService holidaySvc;

    /* Public Methods */

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveRequestData> allRequests(Long orgId) {
        List<Long> orgUserIds = userSvc.findByOrg(orgId).stream().map(UserPojo::getId).toList();
        Map<Long, EmpPojo> applicants = employeeSvc.findByOrg(orgId).stream()
            .collect(Collectors.toMap(EmpPojo::getUserId, Function.identity()));
        Map<Long, LeaveTypePojo> types = typeSvc.listAll(orgId).stream()
            .collect(Collectors.toMap(LeaveTypePojo::getId, Function.identity()));
        return requestSvc.findByUsers(orgUserIds).stream()
            .map(r -> LeaveMapper.toSummary(r, applicants.get(r.getUserId()), types.get(r.getLeaveTypeId())))
            .toList();
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<LeaveTypeData> leaveTypes(Long orgId) {
        return typeSvc.listActive(orgId).stream().map(LeaveMapper::toTypeData).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public IdData createLeaveType(Long orgId, LeaveTypeForm form) {
        LeaveTypePojo type = typeSvc.create(orgId, form.getName(), form.getInfoNote(),
            form.getRequiresAdvanceNoticeDays(), parseDays(form.getDaysPerYear()));
        IdData data = new IdData();
        data.setId(type.getId());
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateLeaveType(Long orgId, Long id, LeaveTypeForm form) {
        typeSvc.update(orgId, id, form.getCode(), form.getName(), form.getInfoNote(),
            form.getRequiresAdvanceNoticeDays(), parseDays(form.getDaysPerYear()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteLeaveType(Long orgId, Long id) {
        typeSvc.deactivate(orgId, id);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<HolidayData> holidays(Long orgId, Integer year) {
        int y = year != null ? year : Year.now().getValue();
        return holidaySvc.list(orgId, y).stream()
            .map(HrLeaveFlow::toHolidayData)
            .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public IdData createHoliday(Long orgId, HolidayForm form) {
        PublicHolidayPojo holiday = holidaySvc.create(orgId, form.getName(), form.getDate());
        IdData data = new IdData();
        data.setId(holiday.getId());
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateHoliday(Long orgId, Long id, HolidayForm form) {
        holidaySvc.update(orgId, id, form.getName(), form.getDate());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteHoliday(Long orgId, Long id) {
        holidaySvc.delete(orgId, id);
    }

    /* Private Helper Methods */

    // The frontend sends the label string ("14 days/year" or plain "14"); pull the
    // leading number out. Blank / no number -> no entitlement (null). Form-string
    // parsing belongs here, not in LeaveTypeService (a *Service must not depend on *Form).
    private static BigDecimal parseDays(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = LEADING_NUMBER.matcher(raw);
        return m.find() ? new BigDecimal(m.group()) : null;
    }

    private static HolidayData toHolidayData(PublicHolidayPojo holiday) {
        HolidayData data = new HolidayData();
        data.setId(holiday.getId());
        data.setName(holiday.getName());
        data.setDate(holiday.getHolidayDate());
        data.setYear(holiday.getHolidayDate().getYear());
        return data;
    }
}
