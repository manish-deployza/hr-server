package com.leaveapp.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.OrgData;
import com.leaveapp.model.form.OrgForm;
import com.leaveapp.workflow.OrgFlow;

// Org-settings screen. Scoped to the caller's own org (id comes from the JWT, not the URL).
@RestController
@RequestMapping("/api/organization")
@PreAuthorize("hasRole('ORG_ADMIN')")
public class OrgController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(OrgController.class);

    @Autowired
    private OrgFlow orgFlow;

    /* Public Methods */

    @GetMapping
    public OrgData get() {
        Long orgId = requireOrgId();
        log.info("GET /api/organization orgId={}", orgId);
        return orgFlow.get(orgId);
    }

    @PatchMapping
    public OrgData update(@RequestBody OrgForm form) {
        Long orgId = requireOrgId();
        log.info("PATCH /api/organization orgId={}", orgId);
        return orgFlow.update(orgId, form);
    }
}
