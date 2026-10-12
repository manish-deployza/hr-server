package com.leaveapp.rest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.EmpImportResultData;
import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.form.EmpDetailsForm;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.model.form.EmpImportForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.EmpDetailsFlow;
import com.leaveapp.workflow.EmpImportFlow;
import com.leaveapp.workflow.HrEmpFlow;

// HR / Org admin manages employee accounts in their own org. orgId is always
// the JWT claim, never a URL or body field. This controller adds the org scope
// and (on update/deactivate) whether this caller may touch this target's role --
// HrEmpFlow receives only authorized primitives.
@RestController
@RequestMapping("/api/hr/employees")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR')")
public class HrEmpController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(HrEmpController.class);

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private EmpDetailsFlow detailsFlow;

    @Autowired
    private EmpImportFlow importFlow;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    @GetMapping
    public List<EmpRowData> list() {
        Long orgId = requireOrgId();
        log.info("GET /api/hr/employees orgId={}", orgId);
        return hrEmpFlow.list(orgId);
    }

    // No create here: only an org admin creates users (AdminUserController) -- HR
    // manages the employees afterwards. Bulk import creates users too, so it's the
    // org admin's as well.
    //
    // Bulk create from CSV/TSV rows the frontend already parsed to JSON. Best-effort:
    // every valid row is created, invalid rows are reported, not fatal. Never logs a
    // password or the row emails -- only counts.
    @PostMapping("/import")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public EmpImportResultData importEmployees(@RequestBody EmpImportForm form) {
        Long orgId = requireOrgId();
        int rowCount = form.getRows() == null ? 0 : form.getRows().size();

        log.info("POST /api/hr/employees/import orgId={} rows={}", orgId, rowCount);
        EmpImportResultData result = importFlow.importEmployees(orgId, form.getRows());
        log.info("POST /api/hr/employees/import completed orgId={} created={} skipped={}",
            orgId, result.getCreated(), result.getSkipped().size());
        return result;
    }

    // HR/admin edit of what the employee filled in at onboarding (personal, address,
    // emergency contact, pay and IDs). Same per-target rule as update; audited by section.
    @PatchMapping("/{id}/details")
    public AckData updateDetails(@PathVariable("id") Long id, @RequestBody EmpDetailsForm form) {
        Long orgId = requireOrgId();
        requireManageable(orgId, id);

        log.info("PATCH /api/hr/employees/{}/details orgId={}", id, orgId);
        detailsFlow.update(requireUserId(), id, form);
        return new AckData();
    }

    @PatchMapping("/{id}")
    public EmpRowData update(@PathVariable("id") Long id, @RequestBody EmpForm form) {
        Long orgId = requireOrgId();
        checkPayloadRole(form);
        requireManageable(orgId, id);

        log.info("PATCH /api/hr/employees/{} orgId={}", id, orgId);
        return hrEmpFlow.update(orgId, id, form);
    }

    // Soft-delete: status=EXITED, date_of_exit=today. No hard delete (leave history
    // keeps its FKs). Idempotent -- deleting an already-exited account is a no-op 200.
    @DeleteMapping("/{id}")
    public AckData deactivate(@PathVariable("id") Long id) {
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

    // Body-dependent role rules (the plain ORG_ADMIN/HR gate can't express
    // these). Only fires when the payload actually carries a `role`.
    private void checkPayloadRole(EmpForm form) {
        if (UserRole.ORG_ADMIN == form.getRole()) {
            throw new BadRequestException("cannot assign the org admin role here");
        }
        if (UserRole.HR == form.getRole() && !hasRole(UserRole.ORG_ADMIN)) {
            throw new AccessDeniedException("only an org admin can grant the HR admin role");
        }
    }

    // Object-level check for update/deactivate: 404 on cross-org or unknown id (no
    // existence leak), 403 when an HR caller aims at a non-EMPLOYEE row -- only
    // an org admin may modify those.
    private void requireManageable(Long orgId, Long targetId) {
        UserPojo target = userSvc.findById(targetId);
        requireOrgMatch(orgId, target.getOrgId(), targetId);
        if (!hasRole(UserRole.ORG_ADMIN) && UserRole.EMPLOYEE != target.getRole()) {
            throw new AccessDeniedException("only an org admin can modify this account");
        }
    }
}
