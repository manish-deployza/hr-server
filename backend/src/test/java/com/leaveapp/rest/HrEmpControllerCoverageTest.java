package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.EmpImportResultData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.EmpDetailsForm;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.EmpImportForm;
import com.leaveapp.model.form.EmpImportRowForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.EmpDetailsFlow;
import com.leaveapp.workflow.EmpImportFlow;
import com.leaveapp.workflow.HrEmpFlow;

// Companion to HrEmpControllerTest, which leaves bulk import, the onboarding-details edit,
// and the "org admin may grant HR" arm untouched. Import is ORG_ADMIN-only on top of the
// class-level ORG_ADMIN/HR gate; details edits share update's per-target rule.
public class HrEmpControllerCoverageTest {

    /* Variables */

    private final RecordingImportFlow importFlow = new RecordingImportFlow();
    private final RecordingDetailsFlow detailsFlow = new RecordingDetailsFlow();
    private final RecordingHrEmpFlow hrEmpFlow = new RecordingHrEmpFlow();
    private final StubUserService userSvc = new StubUserService();
    private final HrEmpController target = new HrEmpController();
    private final HrEmpController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "importFlow", importFlow);
        ReflectionTestUtils.setField(target, "detailsFlow", detailsFlow);
        ReflectionTestUtils.setField(target, "hrEmpFlow", hrEmpFlow);
        ReflectionTestUtils.setField(target, "userSvc", userSvc);
        userSvc.put(77L, 9L, UserRole.EMPLOYEE);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ---- import ----

    @Test
    public void importEmployees_orgAdmin_passesOrgIdAndRows() {
        authenticate(9L, "ROLE_ORG_ADMIN");
        EmpImportForm form = new EmpImportForm();
        form.setRows(List.of(new EmpImportRowForm(), new EmpImportRowForm()));

        assertSame(importFlow.result, controller.importEmployees(form));
        assertEquals(9L, importFlow.orgId);
        assertSame(form.getRows(), importFlow.rows);
    }

    @Test
    public void importEmployees_nullRows_passedThroughForFlowToReject() {
        authenticate(9L, "ROLE_ORG_ADMIN");

        assertSame(importFlow.result, controller.importEmployees(new EmpImportForm()));
        assertEquals(null, importFlow.rows);
    }

    @Test
    public void importEmployees_hr_forbidden() {
        authenticate(9L, "ROLE_HR");
        assertThrows(AccessDeniedException.class, () -> controller.importEmployees(new EmpImportForm()));
        assertEquals(null, importFlow.orgId);
    }

    @Test
    public void importEmployees_employeeAndAnonymous_rejected() {
        authenticate(9L, "ROLE_EMPLOYEE");
        assertThrows(AccessDeniedException.class, () -> controller.importEmployees(new EmpImportForm()));
        SecurityContextHolder.clearContext();
        assertThrows(Exception.class, () -> controller.importEmployees(new EmpImportForm()));
    }

    // ---- details ----

    @Test
    public void updateDetails_hrOnEmployeeTarget_passesCallerAndTargetToFlow() {
        authenticate(9L, "ROLE_HR");
        EmpDetailsForm form = new EmpDetailsForm();

        assertNotNull(controller.updateDetails(77L, form));
        assertEquals(List.of("details:1:77"), detailsFlow.log);
        assertSame(form, detailsFlow.form);
    }

    @Test
    public void updateDetails_orgAdminOnHrTarget_allowed() {
        authenticate(9L, "ROLE_ORG_ADMIN");
        userSvc.put(77L, 9L, UserRole.HR);
        controller.updateDetails(77L, new EmpDetailsForm());
        assertEquals(List.of("details:1:77"), detailsFlow.log);
    }

    @Test
    public void updateDetails_hrOnHrTarget_forbidden() {
        authenticate(9L, "ROLE_HR");
        userSvc.put(77L, 9L, UserRole.HR);
        assertThrows(AccessDeniedException.class, () -> controller.updateDetails(77L, new EmpDetailsForm()));
        assertTrue(detailsFlow.log.isEmpty());
    }

    @Test
    public void updateDetails_crossOrgOrUnknownTarget_notFound() {
        authenticate(9L, "ROLE_HR");
        userSvc.put(77L, 999L, UserRole.EMPLOYEE);
        assertThrows(NotFoundException.class, () -> controller.updateDetails(77L, new EmpDetailsForm()));
        assertThrows(NotFoundException.class, () -> controller.updateDetails(12345L, new EmpDetailsForm()));
        assertTrue(detailsFlow.log.isEmpty());
    }

    @Test
    public void updateDetails_employeeRole_forbidden() {
        authenticate(9L, "ROLE_EMPLOYEE");
        assertThrows(AccessDeniedException.class, () -> controller.updateDetails(77L, new EmpDetailsForm()));
    }

    // ---- update: payload role arms not covered by HrEmpControllerTest ----

    @Test
    public void update_orgAdminGrantingHr_allowed() {
        authenticate(9L, "ROLE_ORG_ADMIN");
        EmpForm form = new EmpForm();
        form.setRole(UserRole.HR);

        assertSame(hrEmpFlow.updateReturns, controller.update(77L, form));
    }

    @Test
    public void update_hrAssigningEmployeeRole_allowed() {
        authenticate(9L, "ROLE_HR");
        EmpForm form = new EmpForm();
        form.setRole(UserRole.EMPLOYEE);

        assertSame(hrEmpFlow.updateReturns, controller.update(77L, form));
    }

    /* Private Helper Methods */

    private static void authenticate(Long orgId, String authority) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(orgId);
        // hasRole() in the controller reads UserPrincipal.getRoles(); @PreAuthorize reads the authority.
        p.setRoles(List.of(UserRole.valueOf(authority.substring("ROLE_".length()))));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /* Test Doubles */

    private static final class StubUserService extends UserService {
        private final Map<Long, UserPojo> byId = new HashMap<>();

        void put(Long id, Long orgId, UserRole role) {
            UserPojo u = new UserPojo();
            u.setId(id);
            u.setOrgId(orgId);
            u.setRole(role);
            byId.put(id, u);
        }

        @Override
        public UserPojo findById(Long id) {
            UserPojo u = byId.get(id);
            if (u == null) {
                throw new NotFoundException("user not found id=" + id);
            }
            return u;
        }
    }

    private static final class RecordingImportFlow extends EmpImportFlow {
        private final EmpImportResultData result = new EmpImportResultData();
        private Long orgId;
        private List<EmpImportRowForm> rows;

        private RecordingImportFlow() {
            result.setCreated(1);
            result.setSkipped(List.of("row 2: bad"));
        }

        @Override
        public EmpImportResultData importEmployees(Long orgId, List<EmpImportRowForm> rows) {
            this.orgId = orgId;
            this.rows = rows;
            return result;
        }
    }

    private static final class RecordingDetailsFlow extends EmpDetailsFlow {
        private final List<String> log = new ArrayList<>();
        private EmpDetailsForm form;

        @Override
        public void update(Long performedBy, Long targetId, EmpDetailsForm form) {
            log.add("details:" + performedBy + ":" + targetId);
            this.form = form;
        }
    }

    private static final class RecordingHrEmpFlow extends HrEmpFlow {
        private final com.leaveapp.model.data.EmpRowData updateReturns = new com.leaveapp.model.data.EmpRowData();

        @Override
        public com.leaveapp.model.data.EmpRowData update(Long orgId, Long targetId, EmpForm form) {
            return updateReturns;
        }
    }
}
