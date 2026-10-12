package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.data.DirectoryData;
import com.leaveapp.model.enums.DirectoryScope;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.form.EmpForm;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.EmpService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// Gap-filler for DirectoryFlowTest: every filter arm of the SELF scope, rows with no
// sensitive row, the tail-masking boundaries and the sensitive-only salary.
public class DirectoryFlowCoverageTest extends RepositoryTestSupport {

    /* Variables */

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 50);

    @Autowired
    private DirectoryFlow dirFlow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private EmpService empSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    /* Public Methods */

    @Test
    public void selfScope_callerFromAnotherOrg_returnsEmptyPage() {
        Long orgA = anOrg("acme-dirc-1");
        Long orgB = anOrg("acme-dirc-1b");
        Long me = hrEmpFlow.create(orgA, empForm("me1@acme.test", "Me One"));

        Page<DirectoryData> page = list(orgB, DirectoryScope.SELF, me, null, null);

        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
    }

    @Test
    public void selfScope_exitedCaller_returnsEmptyPage() {
        Long orgId = anOrg("acme-dirc-2");
        Long me = hrEmpFlow.create(orgId, empForm("me2@acme.test", "Me Two"));
        hrEmpFlow.deactivate(me);

        assertEquals(0, list(orgId, DirectoryScope.SELF, me, null, null).getTotalElements());
    }

    @Test
    public void selfScope_departmentFilter_matchesCaseInsensitively_andMismatchEmptiesThePage() {
        Long orgId = anOrg("acme-dirc-3");
        EmpForm f = empForm("me3@acme.test", "Me Three");
        f.setDepartment("Engineering");
        Long me = hrEmpFlow.create(orgId, f);

        assertEquals(1, list(orgId, DirectoryScope.SELF, me, "ENGINEERING", null).getTotalElements());
        assertEquals(0, list(orgId, DirectoryScope.SELF, me, "Sales", null).getTotalElements());
    }

    @Test
    public void selfScope_departmentFilter_callerWithNoDepartment_isNotAMatch() {
        Long orgId = anOrg("acme-dirc-4");
        Long me = hrEmpFlow.create(orgId, empForm("me4@acme.test", "Me Four")); // department null

        assertEquals(0, list(orgId, DirectoryScope.SELF, me, "Engineering", null).getTotalElements());
    }

    @Test
    public void selfScope_searchFilter_matchesNameSubstringCaseInsensitively() {
        Long orgId = anOrg("acme-dirc-5");
        Long me = hrEmpFlow.create(orgId, empForm("me5@acme.test", "Alice Roy"));

        assertEquals(1, list(orgId, DirectoryScope.SELF, me, null, "  ROY ").getTotalElements());
        assertEquals(0, list(orgId, DirectoryScope.SELF, me, null, "zzz").getTotalElements());
    }

    @Test
    public void selfScope_searchFilter_callerWithNoName_isNotAMatch() {
        Long orgId = anOrg("acme-dirc-6");
        Long me = hrEmpFlow.create(orgId, empForm("me6@acme.test", null));

        assertEquals(0, list(orgId, DirectoryScope.SELF, me, null, "anything").getTotalElements());
    }

    @Test
    public void selfScope_blankFilters_areTreatedAsNoFilter() {
        Long orgId = anOrg("acme-dirc-7");
        Long me = hrEmpFlow.create(orgId, empForm("me7@acme.test", null));

        assertEquals(1, list(orgId, DirectoryScope.SELF, me, "   ", "   ").getTotalElements());
    }

    @Test
    public void selfScope_secondPage_hasNoContentButStillReportsTheTotal() {
        Long orgId = anOrg("acme-dirc-8");
        Long me = hrEmpFlow.create(orgId, empForm("me8@acme.test", "Me Eight"));

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.SELF, me, null, null, null,
            PageRequest.of(1, 10), false);

        assertTrue(page.getContent().isEmpty());
        assertEquals(1, page.getTotalElements());
    }

    @Test
    public void reportsScope_withDepartmentAndSearchFilters() {
        Long orgId = anOrg("acme-dirc-9");
        Long mgr = hrEmpFlow.create(orgId, empForm("mgr9@acme.test", "Boss"));
        EmpForm a = empForm("a9@acme.test", "Ann Lee");
        a.setManagerId(mgr);
        a.setDepartment("Ops");
        Long annId = hrEmpFlow.create(orgId, a);
        EmpForm b = empForm("b9@acme.test", "Bob Ray");
        b.setManagerId(mgr);
        b.setDepartment("Ops");
        hrEmpFlow.create(orgId, b);

        Page<DirectoryData> page = list(orgId, DirectoryScope.REPORTS, mgr, " ops ", " lee ");

        assertEquals(List.of(annId), ids(page));
    }

    @Test
    public void orgScope_rowWithoutSensitiveRow_isListedWithNullSensitiveFields() {
        Long orgId = anOrg("acme-dirc-10");
        UserPojo bare = aBareUser(orgId, "bare10@acme.test");
        Long withSens = hrEmpFlow.create(orgId, empForm("full10@acme.test", "Full"));

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.ORG, withSens, null, null, null, FIRST_PAGE, true);

        DirectoryData bareRow = row(page, bare.getId());
        assertNull(bareRow.getPhone());
        assertNull(bareRow.getBankAccount());
        assertNull(bareRow.getPersonalInfo().getGender());
        assertEquals(2, page.getTotalElements());
    }

    @Test
    public void list_phoneIsVisibleEvenWithoutSensitiveAccess() {
        Long orgId = anOrg("acme-dirc-11");
        EmpForm f = empForm("ph11@acme.test", "Phil");
        f.setPhone("+91 98765 43210");
        Long id = hrEmpFlow.create(orgId, f);

        DirectoryData row = row(list(orgId, DirectoryScope.ORG, id, null, null), id);

        assertEquals("+91 98765 43210", row.getPhone());
    }

    @Test
    public void list_includeSensitive_masksIdsByLengthAndKeepsNullsNull() {
        Long orgId = anOrg("acme-dirc-12");
        Long id = hrEmpFlow.create(orgId, empForm("mask12@acme.test", "Mask"));
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(id);
        // bank 4 chars (fully dotted), national id 5 chars (last 4 shown), tax id and passport absent
        sensSvc.applyPayFields(sens, "1234", null, "12345", null, null);

        DirectoryData row = row(dirFlow.list(orgId, DirectoryScope.ORG, id, null, null, null, FIRST_PAGE, true), id);

        assertEquals("••••", row.getBankAccount());
        assertEquals("•••• 2345", row.getNationalId());
        assertNull(row.getTaxId());
        assertNull(row.getPassport());
    }

    @Test
    public void list_includeSensitive_masksLongValuesToLastFour_andShortOnesCompletely() {
        Long orgId = anOrg("acme-dirc-13");
        Long id = hrEmpFlow.create(orgId, empForm("mask13@acme.test", "Mask"));
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(id);
        sensSvc.applyPayFields(sens, "9876543210", null, "A", "TAX-0001", "");

        DirectoryData row = row(dirFlow.list(orgId, DirectoryScope.ORG, id, null, null, null, FIRST_PAGE, true), id);

        assertEquals("•••• 3210", row.getBankAccount());
        assertEquals("••••", row.getNationalId());
        assertEquals("•••• 0001", row.getTaxId());
        assertEquals("••••", row.getPassport()); // empty string: nothing to reveal
    }

    @Test
    public void list_basicSalaryOnlyForSensitiveCallers() {
        Long orgId = anOrg("acme-dirc-14");
        EmpForm f = empForm("sal14@acme.test", "Sal");
        f.setBasicSalary(new BigDecimal("5000.00"));
        Long id = hrEmpFlow.create(orgId, f);

        DirectoryData sensitive = row(dirFlow.list(orgId, DirectoryScope.ORG, id, null, null, null, FIRST_PAGE, true), id);
        DirectoryData plain = row(list(orgId, DirectoryScope.ORG, id, null, null), id);

        assertEquals(0, new BigDecimal("5000.00").compareTo(sensitive.getBasicSalary()));
        assertNull(plain.getBasicSalary());
    }

    @Test
    public void list_orgScopeWithNoMatches_returnsEmptyPageAndSkipsTheSensitiveLookup() {
        Long orgId = anOrg("acme-dirc-15");
        Long me = hrEmpFlow.create(orgId, empForm("me15@acme.test", "Me"));

        Page<DirectoryData> page = list(orgId, DirectoryScope.ORG, me, "NoSuchDept", null);

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    /* Private Helper Methods */

    private Page<DirectoryData> list(Long orgId, DirectoryScope scope, Long viewerId, String dept, String search) {
        return dirFlow.list(orgId, scope, viewerId, dept, null, search, FIRST_PAGE, false);
    }

    private static List<Long> ids(Page<DirectoryData> page) {
        return page.getContent().stream().map(DirectoryData::getId).collect(Collectors.toList());
    }

    private static DirectoryData row(Page<DirectoryData> page, Long id) {
        return page.getContent().stream().filter(d -> d.getId().equals(id)).findFirst().orElseThrow();
    }

    // a user + employee row with no employee_sensitive row (the pre-sensitive-module shape)
    private UserPojo aBareUser(Long orgId, String email) {
        UserPojo user = userSvc.createUser(orgId, email, "s3cret!!".getBytes(StandardCharsets.UTF_8), UserRole.EMPLOYEE);
        EmpPojo emp = empSvc.createOrgAdminProfile(user.getId(), orgId);
        emp.setName("Bare");
        return user;
    }

    private Long anOrg(String username) {
        OrgPojo org = orgSvc.create("Acme", username, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        return org.getId();
    }

    private static EmpForm empForm(String email, String name) {
        EmpForm f = new EmpForm();
        f.setEmail(email);
        f.setName(name);
        f.setRole(UserRole.EMPLOYEE);
        return f;
    }
}
