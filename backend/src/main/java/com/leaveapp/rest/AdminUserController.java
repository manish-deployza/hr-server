package com.leaveapp.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.AckData;
import com.leaveapp.model.data.IdData;
import com.leaveapp.model.form.UserInviteForm;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.UserService;
import com.leaveapp.workflow.HrEmpFlow;

// Org admin invites users into their own org: name + email + role, then the invite
// mail does the rest (set password -> onboarding form). HR can't create users; HR
// manages the employees after they exist (HrEmpController). orgId is always the JWT
// claim, never a URL or body field.
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ORG_ADMIN')")
public class AdminUserController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private UserService userSvc;

    /* Public Methods */

    @PostMapping
    public IdData invite(@RequestBody UserInviteForm form) {
        Long orgId = requireOrgId();

        log.info("POST /api/admin/users orgId={} role={}", orgId, form.getRole());
        Long id = hrEmpFlow.invite(orgId, form);
        log.info("POST /api/admin/users completed userId={}", id);
        IdData data = new IdData();
        data.setId(id);
        return data;
    }

    // For an invite whose link expired (or never arrived): mints a fresh link.
    @PostMapping("/{id}/resend-invite")
    public AckData resendInvite(@PathVariable("id") Long id) {
        Long orgId = requireOrgId();
        UserPojo target = userSvc.findById(id);
        requireOrgMatch(orgId, target.getOrgId(), id);

        log.info("POST /api/admin/users/{}/resend-invite orgId={}", id, orgId);
        hrEmpFlow.resendInvite(id);
        return new AckData();
    }
}
