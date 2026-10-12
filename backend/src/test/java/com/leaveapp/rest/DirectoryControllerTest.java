package com.leaveapp.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.data.DirectoryData;
import com.leaveapp.model.enums.DirectoryScope;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.security.UserPrincipal;
import com.leaveapp.service.EmpService;
import com.leaveapp.workflow.DirectoryFlow;

// Plain unit test -- no Spring/web context. Covers what the controller adds over the
// Flow: scope resolution (ORG_ADMIN/HR -> ORG, a caller with reports -> REPORTS,
// else SELF, ORG_ADMIN-with-reports still ORG), the managerId-filter-is-admin-only 403,
// page/size clamping and the sensitive-field flag (ORG_ADMIN only).
public class DirectoryControllerTest {

    /* Variables */

    private final RecordingFlow flow = new RecordingFlow();
    private final StubEmployeeService empSvc = new StubEmployeeService();
    private final DirectoryController target = new DirectoryController();
    private final DirectoryController controller = SecurityProxyTestSupport.withPreAuthorize(target);

    {
        ReflectionTestUtils.setField(target, "dirFlow", flow);
        ReflectionTestUtils.setField(target, "empSvc", empSvc);
    }

    /* Public Methods */

    @AfterEach
    public void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void list_asOrgAdmin_resolvesOrgScope() {
        authenticate(9L, UserRole.ORG_ADMIN);

        assertSame(flow.listReturns, controller.list(null, null, null, 0, 50));
        assertEquals(DirectoryScope.ORG, flow.scope);
        assertEquals(9L, flow.orgId);
    }

    @Test
    public void list_asHr_resolvesOrgScope() {
        authenticate(9L, UserRole.HR);
        controller.list(null, null, null, 0, 50);
        assertEquals(DirectoryScope.ORG, flow.scope);
    }

    @Test
    public void list_orgAdminWithReports_stillOrgScope() {
        authenticate(9L, UserRole.ORG_ADMIN);
        empSvc.managers.add(1L); // authenticate() sets principal userId = 1
        controller.list(null, null, null, 0, 50);
        assertEquals(DirectoryScope.ORG, flow.scope);
    }

    @Test
    public void list_plainEmployeeWithReports_reportsScope() {
        authenticate(9L, UserRole.EMPLOYEE);
        empSvc.managers.add(1L);
        controller.list(null, null, null, 0, 50);
        assertEquals(DirectoryScope.REPORTS, flow.scope);
    }

    @Test
    public void list_plainEmployeeNoReports_selfScope() {
        authenticate(9L, UserRole.EMPLOYEE);
        controller.list(null, null, null, 0, 50);
        assertEquals(DirectoryScope.SELF, flow.scope);
    }

    @Test
    public void list_managerIdFilter_nonOrgScope_forbidden() {
        authenticate(9L, UserRole.EMPLOYEE);
        assertThrows(AccessDeniedException.class, () -> controller.list(null, 5L, null, 0, 50));
    }

    @Test
    public void list_managerIdFilter_orgScope_allowed() {
        authenticate(9L, UserRole.ORG_ADMIN);
        controller.list(null, 5L, null, 0, 50);
        assertEquals(5L, flow.managerId);
    }

    @Test
    public void list_sizeOverMax_clampedTo200() {
        authenticate(9L, UserRole.ORG_ADMIN);
        controller.list(null, null, null, 0, 999);
        assertEquals(200, flow.pageable.getPageSize());
    }

    @Test
    public void list_sizeZeroOrLess_defaultsTo50() {
        authenticate(9L, UserRole.ORG_ADMIN);
        controller.list(null, null, null, 0, 0);
        assertEquals(50, flow.pageable.getPageSize());
    }

    @Test
    public void list_negativePage_clampedToZero() {
        authenticate(9L, UserRole.ORG_ADMIN);
        controller.list(null, null, null, -3, 50);
        assertEquals(0, flow.pageable.getPageNumber());
    }

    @Test
    public void list_noPrincipal_notAuthenticated() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
            () -> controller.list(null, null, null, 0, 50));
    }

    // Regression guard for the access-control gap the Sensitive Module closes: ORG_ADMIN
    // and HR both resolve to DirectoryScope.ORG, but only ORG_ADMIN may receive Steps
    // 4/5/6/9. Gating on scope instead of role here would hand HR every employee's bank
    // details (see prompts/features/employee-directory.md, "Sensitive Module" -> Access
    // Control).
    @Test
    public void list_asOrgAdmin_includesSensitiveFlagTrue() {
        authenticate(9L, UserRole.ORG_ADMIN);
        controller.list(null, null, null, 0, 50);
        assertTrue(flow.includeSensitive);
    }

    @Test
    public void list_asHr_includesSensitiveFlagTrue() {
        authenticate(9L, UserRole.HR);
        controller.list(null, null, null, 0, 50);
        assertTrue(flow.includeSensitive);
    }

    @Test
    public void list_asManagerOrEmployee_includesSensitiveFlagFalse() {
        authenticate(9L, UserRole.EMPLOYEE);
        empSvc.managers.add(1L); // REPORTS scope
        controller.list(null, null, null, 0, 50);
        assertFalse(flow.includeSensitive);
    }

    /* Private Helper Methods */

    private static void authenticate(Long orgId, UserRole role) {
        UserPrincipal p = new UserPrincipal();
        p.setUserId(1L);
        p.setOrgId(orgId);
        p.setRoles(List.of(role));
        // "ROLE_" + name(), matching JwtAuthFilter -- @PreAuthorize's hasRole() reads
        // these GrantedAuthority values, not UserPrincipal.getRoles() directly.
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, authorities));
    }

    /* Test Doubles */

    private static final class StubEmployeeService extends EmpService {
        private final Set<Long> managers = new HashSet<>();

        @Override
        public boolean existsManager(Long userId) {
            return managers.contains(userId);
        }
    }

    private static final class RecordingFlow extends DirectoryFlow {
        private final Page<DirectoryData> listReturns = new PageImpl<>(List.of(new DirectoryData()));
        private DirectoryScope scope;
        private Long orgId;
        private Long managerId;
        private Pageable pageable;
        private boolean includeSensitive;

        @Override
        public Page<DirectoryData> list(Long orgId, DirectoryScope scope, Long viewerId,
            String department, Long managerId, String search, Pageable pg, boolean includeSensitive) {
            this.orgId = orgId;
            this.scope = scope;
            this.managerId = managerId;
            this.pageable = pg;
            this.includeSensitive = includeSensitive;
            return listReturns;
        }
    }
}
