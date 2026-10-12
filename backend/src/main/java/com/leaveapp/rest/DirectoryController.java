package com.leaveapp.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.leaveapp.model.data.DirectoryData;
import com.leaveapp.model.enums.DirectoryScope;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.service.EmpService;
import com.leaveapp.workflow.DirectoryFlow;

// Org-scoped employee directory: any authenticated role can GET, scoped server-side
// to ORG/REPORTS/SELF (see resolveScope). Read only -- users are created via
// AdminUserController and edited via HrEmpController. orgId is always the JWT claim,
// never a URL or body field.
@RestController
@RequestMapping("/api/directory")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'HR', 'EMPLOYEE')")
public class DirectoryController extends AbstractController {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(DirectoryController.class);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    @Autowired
    private DirectoryFlow dirFlow;

    @Autowired
    private EmpService empSvc;

    /* Public Methods */

    @GetMapping
    public Page<DirectoryData> list(
        @RequestParam(name = "department", required = false) String department,
        @RequestParam(name = "managerId", required = false) Long managerId,
        @RequestParam(name = "search", required = false) String search,
        @RequestParam(name = "page", required = false, defaultValue = "0") int page,
        @RequestParam(name = "size", required = false, defaultValue = "50") int size) {
        Long userId = requireUserId();
        Long orgId = requireOrgId();
        DirectoryScope scope = resolveScope(userId);
        if (managerId != null && DirectoryScope.ORG != scope) {
            throw new AccessDeniedException("managerId filter requires org-wide access");
        }
        Pageable pg = PageRequest.of(Math.max(page, 0), clampSize(size));
        // The onboarding details (personal, address, emergency, pay and IDs) and salary go
        // to ORG_ADMIN and HR, who manage employees -- deliberately NOT derived from
        // `DirectoryScope.ORG == scope`, so a future org-wide scope for other roles cannot
        // leak them. Managers and employees never receive them.
        boolean includeSensitive = hasRole(UserRole.ORG_ADMIN) || hasRole(UserRole.HR);

        log.info("GET /api/directory orgId={} scope={} department={} managerId={} search={}",
            orgId, scope, department, managerId, search);
        return dirFlow.list(orgId, scope, userId, department, managerId, search, pg, includeSensitive);
    }

    /* Private Helper Methods */

    // ORG_ADMIN/HR see the whole org; a caller with >=1 direct report (no MANAGER
    // role exists -- derived from employee.manager_id, same as ManagerLeaveFlow/GET
    // /api/manager/team) sees their reports; everyone else sees only themselves. Order
    // matters: an ORG_ADMIN who also has reports still gets ORG, not REPORTS.
    private DirectoryScope resolveScope(Long userId) {
        if (hasRole(UserRole.ORG_ADMIN) || hasRole(UserRole.HR)) {
            return DirectoryScope.ORG;
        }
        if (empSvc.existsManager(userId)) {
            return DirectoryScope.REPORTS;
        }
        return DirectoryScope.SELF;
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
