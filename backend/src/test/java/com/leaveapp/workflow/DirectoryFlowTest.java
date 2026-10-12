
package com.leaveapp.workflow;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.leaveapp.pojo.AddressPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.service.EmpSensitiveService;
import com.leaveapp.service.OrgService;
import com.leaveapp.service.SubscriptionService;

public class DirectoryFlowTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private DirectoryFlow dirFlow;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private OrgService orgSvc;

    @Autowired
    private SubscriptionService subscriptionSvc;

    private static final Pageable UNPAGED_50 = PageRequest.of(0, 50);

    /* Public Methods */

    @Test
    public void orgScope_excludesExited_includesActiveAndOnNotice() {
        Long orgId = anOrg("acme-dir-1");
        Long active = hrEmpFlow.create(orgId, empForm("active@acme.test"));
        Long exited = hrEmpFlow.create(orgId, empForm("exited@acme.test"));
        hrEmpFlow.deactivate(exited);

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.ORG, active, null, null, null, UNPAGED_50, false);

        List<Long> ids = page.getContent().stream().map(DirectoryData::getId).collect(Collectors.toList());
        assertTrue(ids.contains(active));
        assertFalse(ids.contains(exited));
    }

    @Test
    public void orgScope_excludesAnotherOrgsEmployees() {
        Long orgId = anOrg("acme-dir-2");
        Long otherOrgId = anOrg("acme-dir-2b");
        Long mine = hrEmpFlow.create(orgId, empForm("mine@acme.test"));
        hrEmpFlow.create(otherOrgId, empForm("theirs@acme.test"));

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.ORG, mine, null, null, null, UNPAGED_50, false);

        assertEquals(1, page.getTotalElements());
        assertEquals(mine, page.getContent().get(0).getId());
    }

    @Test
    public void reportsScope_returnsOnlyDirectReports() {
        Long orgId = anOrg("acme-dir-3");
        Long mgr = hrEmpFlow.create(orgId, empForm("mgr3@acme.test"));
        EmpForm reportForm = empForm("report3@acme.test");
        reportForm.setManagerId(mgr);
        Long report = hrEmpFlow.create(orgId, reportForm);
        Long stranger = hrEmpFlow.create(orgId, empForm("stranger3@acme.test"));

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.REPORTS, mgr, null, null, null, UNPAGED_50, false);

        List<Long> ids = page.getContent().stream().map(DirectoryData::getId).collect(Collectors.toList());
        assertEquals(List.of(report), ids);
        assertFalse(ids.contains(stranger));
    }

    @Test
    public void selfScope_returnsOnlyCaller() {
        Long orgId = anOrg("acme-dir-4");
        Long me = hrEmpFlow.create(orgId, empForm("me4@acme.test"));
        hrEmpFlow.create(orgId, empForm("other4@acme.test"));

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.SELF, me, null, null, null, UNPAGED_50, false);

        assertEquals(1, page.getTotalElements());
        assertEquals(me, page.getContent().get(0).getId());
    }

    @Test
    public void departmentFilter_caseInsensitiveExactMatch() {
        Long orgId = anOrg("acme-dir-5");
        Long caller = hrEmpFlow.create(orgId, empForm("caller5@acme.test"));
        EmpForm eng = empForm("eng5@acme.test");
        eng.setDepartment("Engineering");
        Long engId = hrEmpFlow.create(orgId, eng);
        EmpForm sales = empForm("sales5@acme.test");
        sales.setDepartment("Sales");
        hrEmpFlow.create(orgId, sales);

        Page<DirectoryData> page =
            dirFlow.list(orgId, DirectoryScope.ORG, caller, "engineering", null, null, UNPAGED_50, false);

        List<Long> ids = page.getContent().stream().map(DirectoryData::getId).collect(Collectors.toList());
        assertEquals(List.of(engId), ids);
    }

    @Test
    public void managerIdFilter_exactMatch() {
        Long orgId = anOrg("acme-dir-6");
        Long mgr = hrEmpFlow.create(orgId, empForm("mgr6@acme.test"));
        EmpForm reportForm = empForm("report6@acme.test");
        reportForm.setManagerId(mgr);
        Long report = hrEmpFlow.create(orgId, reportForm);
        hrEmpFlow.create(orgId, empForm("unrelated6@acme.test"));

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.ORG, mgr, null, mgr, null, UNPAGED_50, false);

        List<Long> ids = page.getContent().stream().map(DirectoryData::getId).collect(Collectors.toList());
        assertEquals(List.of(report), ids);
    }

    @Test
    public void searchFilter_caseInsensitiveSubstring_nullNameNeverMatches() {
        Long orgId = anOrg("acme-dir-7");
        Long caller = hrEmpFlow.create(orgId, empForm("caller7@acme.test"));
        EmpForm alice = empForm("alice7@acme.test");
        alice.setName("Alice Roy");
        Long aliceId = hrEmpFlow.create(orgId, alice);
        hrEmpFlow.create(orgId, empForm("noname7@acme.test")); // name left null

        Page<DirectoryData> page =
            dirFlow.list(orgId, DirectoryScope.ORG, caller, null, null, "roy", UNPAGED_50, false);

        List<Long> ids = page.getContent().stream().map(DirectoryData::getId).collect(Collectors.toList());
        assertEquals(List.of(aliceId), ids);
    }

    @Test
    public void unknownFilterValue_yieldsEmptyPage_notAnError() {
        Long orgId = anOrg("acme-dir-8");
        Long caller = hrEmpFlow.create(orgId, empForm("caller8@acme.test"));

        Page<DirectoryData> page =
            dirFlow.list(orgId, DirectoryScope.ORG, caller, "NoSuchDept", null, null, UNPAGED_50, false);

        assertEquals(0, page.getTotalElements());
    }

    @Test
    public void pagination_slicesAndReportsTotal() {
        Long orgId = anOrg("acme-dir-9");
        Long caller = hrEmpFlow.create(orgId, empForm("caller9@acme.test"));
        for (int i = 0; i < 4; i++) {
            hrEmpFlow.create(orgId, empForm("p9-" + i + "@acme.test"));
        }
        // 5 total: caller + 4 -- page size 2

        Page<DirectoryData> page0 =
            dirFlow.list(orgId, DirectoryScope.ORG, caller, null, null, null, PageRequest.of(0, 2), false);
        Page<DirectoryData> page2 =
            dirFlow.list(orgId, DirectoryScope.ORG, caller, null, null, null, PageRequest.of(2, 2), false);

        assertEquals(5, page0.getTotalElements());
        assertEquals(2, page0.getContent().size());
        assertEquals(1, page2.getContent().size()); // last page: 5 rows, size 2 -> pages of 2,2,1
    }

    @Test
    public void list_includeSensitiveTrue_returnsStep456Fields_bankAccountMasked() {
        Long orgId = anOrg("acme-dir-16");
        Long id = hrEmpFlow.create(orgId, empForm("newhire16@acme.test"));
        seedSensitive(id);

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.ORG, id, null, null, null, UNPAGED_50, true);
        DirectoryData row = page.getContent().stream().filter(d -> d.getId().equals(id)).findFirst().orElseThrow();
        assertEquals(LocalDate.of(1990, 5, 1), row.getPersonalInfo().getDateOfBirth());
        assertEquals("Non-binary", row.getPersonalInfo().getGender());
        assertEquals("100 Miller St", row.getAddress().getAddress1());
        assertEquals("Bengaluru", row.getAddress().getCity());
        assertEquals("HDFC0001234", row.getBankIfsc());
        assertEquals("•••• 0123", row.getBankAccount()); // never the real value
    }

    @Test
    public void list_includeSensitiveFalse_excludesStep456And9Fields() {
        Long orgId = anOrg("acme-dir-17");
        Long id = hrEmpFlow.create(orgId, empForm("newhire17@acme.test"));
        seedSensitive(id);

        Page<DirectoryData> page = dirFlow.list(orgId, DirectoryScope.ORG, id, null, null, null, UNPAGED_50, false);
        DirectoryData row = page.getContent().stream().filter(d -> d.getId().equals(id)).findFirst().orElseThrow();
        assertNull(row.getPersonalInfo().getGender());
        assertNull(row.getBankAccount());
        assertNull(row.getBasicSalary());
    }

    /* Private Helper Methods */

    private void seedSensitive(Long userId) {
        EmpSensitivePojo sens = sensSvc.findByUserIdOrNull(userId);
        sensSvc.applyPersonalFields(sens, LocalDate.of(1990, 5, 1), "Non-binary", "IN", null, null, null);
        AddressPojo address = new AddressPojo();
        address.setAddress1("100 Miller St");
        address.setCity("Bengaluru");
        address.setCountry("IN");
        address.setPostalCode("560001");
        address.setState("KA");
        sensSvc.applyAddressFields(sens, address, null, null, null);
        sensSvc.applyPayFields(sens, "1234567890123", "HDFC0001234", null, null, null);
    }

    // orgSvc.create bypasses AuthFlow.signup (which is what normally also creates the
    // org_subscription row) -- hrEmpFlow.create now asserts a subscription exists
    // (SubscriptionService.assertSeatAvailable), so every test org needs one too.
    private Long anOrg(String username) {
        OrgPojo org = orgSvc.create("Acme", username, "hr@acme.test", "IN");
        subscriptionSvc.createFreeSubscription(org.getId());
        return org.getId();
    }

    private static EmpForm empForm(String email) {
        EmpForm f = new EmpForm();
        f.setEmail(email);
        f.setRole(UserRole.EMPLOYEE);
        return f;
    }

    private static byte[] bytes(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
