package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.EmployeeRowData;
import com.leaveapp.model.data.IdData;
import com.leaveapp.model.form.EmployeeForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.pojo.UserRole;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.HrEmployeeFlow;

// HR / Org admin manages employee accounts in their own org. organizationId is always
// the JWT claim, never a URL or body field. Every access decision -- the role gate,
// the org scope, and (on update/deactivate) whether this caller may touch this
// target's role -- is made here; HrEmployeeFlow receives only authorized primitives.
@RestController
@RequestMapping("/api/hr/employees")
public class HrEmployeeController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(HrEmployeeController.class);

    @Autowired
    private HrEmployeeFlow hrEmpFlow;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    @GetMapping
    public List<EmployeeRowData> list() {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        Long orgId = requireOrgId();
        log.info("GET /api/hr/employees orgId={}", orgId);
        return hrEmpFlow.list(orgId);
    }

    @PostMapping
    public IdData create(@RequestBody EmployeeForm form) {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        Long orgId = requireOrgId();
        checkPayloadRole(form);

        log.info("POST /api/hr/employees orgId={} role={}", orgId, form.getRole());
        Long id = hrEmpFlow.create(orgId, form);
        log.info("POST /api/hr/employees completed userId={}", id);
        IdData data = new IdData();
        data.setId(id);
        return data;
    }

    @PatchMapping("/{id}")
    public EmployeeRowData update(@PathVariable("id") Long id, @RequestBody EmployeeForm form) {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        Long orgId = requireOrgId();
        checkPayloadRole(form);
        requireManageable(orgId, id);

        log.info("PATCH /api/hr/employees/{} orgId={}", id, orgId);
        return hrEmpFlow.update(orgId, id, form);
    }

    // Soft-delete: is_active=false, status=EXITED, date_of_exit=today. No hard delete
    // (leave history keeps its FKs). Idempotent -- deleting an already-exited account
    // is a no-op 200.
    @DeleteMapping("/{id}")
    public AckData deactivate(@PathVariable("id") Long id) {
        requireAnyRole(UserRole.ORG_ADMIN, UserRole.HR_ADMIN);
        Long orgId = requireOrgId();
        if (id.equals(requireUserId())) {
            throw new BadRequestException("you cannot deactivate your own account");
        }
        requireManageable(orgId, id);

        log.info("DELETE /api/hr/employees/{} orgId={}", id, orgId);
        hrEmpFlow.deactivate(id);
        return new AckData();
    }

    /* Private Helper Methods */

    // Body-dependent role rules (the plain ORG_ADMIN/HR_ADMIN gate can't express
    // these). Only fires when the payload actually carries a `role`.
    private void checkPayloadRole(EmployeeForm form) {
        if (UserRole.ORG_ADMIN == form.getRole()) {
            throw new BadRequestException("cannot assign the org admin role here");
        }
        if (UserRole.HR_ADMIN == form.getRole() && !hasRole(UserRole.ORG_ADMIN)) {
            throw new AccessDeniedException("only an org admin can grant the HR admin role");
        }
    }

    // Object-level check for update/deactivate: 404 on cross-org or unknown id (no
    // existence leak), 403 when an HR_ADMIN caller aims at a non-EMPLOYEE row -- only
    // an org admin may modify those.
    private void requireManageable(Long orgId, Long targetId) {
        UserPojo target = userSvc.findById(targetId);
        if (!orgId.equals(target.getOrganizationId())) {
            throw new NotFoundException("employee not found id=" + targetId);
        }
        if (!hasRole(UserRole.ORG_ADMIN) && UserRole.EMPLOYEE != target.getRole()) {
            throw new AccessDeniedException("only an org admin can modify this account");
        }
    }
}
