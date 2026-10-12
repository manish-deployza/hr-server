package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.ApproverRole;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.exception.NotFoundException;
import com.leaveapp.model.enums.AccrualStrategy;
import com.leaveapp.pojo.LeaveTypePojo;

// Leave-type catalog: listings, applicability filter, lookups, create (code derivation +
// collision suffixing), PATCH-style update, soft delete and the days/year label.
public class LeaveTypeServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private LeaveTypeService typeSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void listActiveAndListAll_differOnDeactivatedTypes() {
        Long orgId = org("lt-1");
        LeaveTypePojo kept = typeSvc.create(orgId, "Casual", null, null, null);
        LeaveTypePojo gone = typeSvc.create(orgId, "Retired", null, null, null);
        typeSvc.deactivate(orgId, gone.getId());

        assertEquals(List.of(kept.getId()), typeSvc.listActive(orgId).stream().map(LeaveTypePojo::getId).toList());
        assertEquals(2, typeSvc.listAll(orgId).size());
    }

    @Test
    public void listApplicable_nullMatchesEveryone_specificMatchesCaseInsensitively_otherExcluded() {
        Long orgId = org("lt-2");
        LeaveTypePojo all = typeSvc.create(orgId, "For All", null, null, null);
        LeaveTypePojo intern = typeSvc.create(orgId, "Intern Only", null, null, null);
        intern.setApplicableType("intern");
        LeaveTypePojo contract = typeSvc.create(orgId, "Contract Only", null, null, null);
        contract.setApplicableType("CONTRACT");
        LeaveTypePojo lowerAll = typeSvc.create(orgId, "Lower All", null, null, null);
        lowerAll.setApplicableType("all");

        List<Long> forIntern = typeSvc.listApplicable(orgId, "INTERN").stream().map(LeaveTypePojo::getId).toList();
        List<Long> forRegular = typeSvc.listApplicable(orgId, "REGULAR").stream().map(LeaveTypePojo::getId).toList();
        List<Long> forNull = typeSvc.listApplicable(orgId, null).stream().map(LeaveTypePojo::getId).toList();

        // "all" is no longer special -- only null means everyone
        assertTrue(forIntern.containsAll(List.of(all.getId(), intern.getId())));
        assertFalse(forIntern.contains(contract.getId()));
        assertFalse(forIntern.contains(lowerAll.getId()));
        assertEquals(List.of(all.getId()), forRegular);
        assertEquals(List.of(all.getId()), forNull);
    }

    @Test
    public void findById_wrongOrgOrUnknown_notFound() {
        Long orgId = org("lt-3");
        Long other = org("lt-3b");
        Long id = typeSvc.create(orgId, "Casual", null, null, null).getId();

        assertEquals(id, typeSvc.findById(orgId, id).getId());
        assertThrows(NotFoundException.class, () -> typeSvc.findById(other, id));
        assertThrows(NotFoundException.class, () -> typeSvc.findById(orgId, -1L));
    }

    @Test
    public void findActive_activeOk_inactiveOrUnknownOrForeignBadRequest() {
        Long orgId = org("lt-4");
        Long other = org("lt-4b");
        Long id = typeSvc.create(orgId, "Casual", null, null, null).getId();

        assertEquals(id, typeSvc.findActive(orgId, id).getId());
        assertThrows(BadRequestException.class, () -> typeSvc.findActive(other, id));
        assertThrows(BadRequestException.class, () -> typeSvc.findActive(orgId, -1L));

        typeSvc.deactivate(orgId, id);

        assertThrows(BadRequestException.class, () -> typeSvc.findActive(orgId, id));
    }

    @Test
    public void create_setsDefaults_andDerivesCodeFromName() {
        Long orgId = org("lt-5");

        LeaveTypePojo t = typeSvc.create(orgId, "  Sick Leave (Paid)  ", "note", 3, new BigDecimal("8"));

        assertEquals("SICK_LEAVE_PAID", t.getCode());
        assertEquals("Sick Leave (Paid)", t.getName());
        assertEquals("note", t.getInfoNote());
        assertEquals(3, t.getNoticeDays());
        assertEquals(0, new BigDecimal("8").compareTo(t.getDaysPerYear()));
        assertNull(t.getApplicableType()); // applies to everyone
        assertEquals(ApproverRole.MANAGER, t.getApproverRole());
        assertEquals(AccrualStrategy.ANNUAL_PRORATA, t.getAccrualStrategy());
        assertTrue(t.isActive());
    }

    @Test
    public void create_blankOrNullName_badRequest() {
        Long orgId = org("lt-6");

        assertThrows(BadRequestException.class, () -> typeSvc.create(orgId, null, null, null, null));
        assertThrows(BadRequestException.class, () -> typeSvc.create(orgId, "   ", null, null, null));
    }

    @Test
    public void create_nameWithoutAlphanumerics_fallsBackToLeaveType() {
        Long orgId = org("lt-7");

        assertEquals("LEAVE_TYPE", typeSvc.create(orgId, "!!! ???", null, null, null).getCode());
    }

    @Test
    public void create_duplicateNames_getNumericSuffixes() {
        Long orgId = org("lt-8");

        assertEquals("CASUAL", typeSvc.create(orgId, "Casual", null, null, null).getCode());
        assertEquals("CASUAL_2", typeSvc.create(orgId, "casual", null, null, null).getCode());
        assertEquals("CASUAL_3", typeSvc.create(orgId, "CASUAL!", null, null, null).getCode());
    }

    @Test
    public void create_sameNameInOtherOrg_keepsPlainCode() {
        Long orgId = org("lt-9");
        Long other = org("lt-9b");
        typeSvc.create(orgId, "Casual", null, null, null);

        assertEquals("CASUAL", typeSvc.create(other, "Casual", null, null, null).getCode());
    }

    @Test
    public void create_veryLongName_codeTruncatedTo64_andSuffixStillFits() {
        Long orgId = org("lt-10");
        String longName = "A".repeat(100);

        String first = typeSvc.create(orgId, longName, null, null, null).getCode();
        String second = typeSvc.create(orgId, longName, null, null, null).getCode();

        assertEquals(64, first.length());
        assertEquals(64, second.length());
        assertTrue(second.endsWith("_2"));
        assertEquals("A".repeat(62) + "_2", second);
    }

    @Test
    public void update_nullFields_leavesEverythingUnchanged() {
        Long orgId = org("lt-11");
        LeaveTypePojo t = typeSvc.create(orgId, "Casual", "note", 3, new BigDecimal("8"));

        typeSvc.update(orgId, t.getId(), null, null, null, null, null);

        LeaveTypePojo after = typeSvc.findById(orgId, t.getId());
        assertEquals("CASUAL", after.getCode());
        assertEquals("Casual", after.getName());
        assertEquals("note", after.getInfoNote());
        assertEquals(3, after.getNoticeDays());
        assertEquals(0, new BigDecimal("8").compareTo(after.getDaysPerYear()));
    }

    @Test
    public void update_allFields_applied_andCodeTrimmed() {
        Long orgId = org("lt-12");
        LeaveTypePojo t = typeSvc.create(orgId, "Casual", "note", 3, new BigDecimal("8"));

        typeSvc.update(orgId, t.getId(), "  CL2  ", "  Casual 2 ", "n2", 9, new BigDecimal("10"));

        LeaveTypePojo after = typeSvc.findById(orgId, t.getId());
        assertEquals("CL2", after.getCode());
        assertEquals("Casual 2", after.getName());
        assertEquals("n2", after.getInfoNote());
        assertEquals(9, after.getNoticeDays());
        assertEquals(0, new BigDecimal("10").compareTo(after.getDaysPerYear()));
    }

    @Test
    public void update_blankOrUnchangedCode_ignored() {
        Long orgId = org("lt-13");
        LeaveTypePojo t = typeSvc.create(orgId, "Casual", null, null, null);

        typeSvc.update(orgId, t.getId(), "   ", null, null, null, null);
        typeSvc.update(orgId, t.getId(), " CASUAL ", null, null, null, null);

        assertEquals("CASUAL", typeSvc.findById(orgId, t.getId()).getCode());
    }

    @Test
    public void update_codeUsedByAnotherType_conflict() {
        Long orgId = org("lt-14");
        typeSvc.create(orgId, "Casual", null, null, null);
        LeaveTypePojo sick = typeSvc.create(orgId, "Sick", null, null, null);

        assertThrows(ConflictException.class,
            () -> typeSvc.update(orgId, sick.getId(), "CASUAL", null, null, null, null));
    }

    @Test
    public void update_otherOrg_notFound() {
        Long orgId = org("lt-15");
        Long other = org("lt-15b");
        LeaveTypePojo t = typeSvc.create(orgId, "Casual", null, null, null);

        assertThrows(NotFoundException.class, () -> typeSvc.update(other, t.getId(), null, "X", null, null, null));
    }

    @Test
    public void deactivate_marksInactive_otherOrgNotFound() {
        Long orgId = org("lt-16");
        Long other = org("lt-16b");
        LeaveTypePojo t = typeSvc.create(orgId, "Casual", null, null, null);

        assertThrows(NotFoundException.class, () -> typeSvc.deactivate(other, t.getId()));
        typeSvc.deactivate(orgId, t.getId());

        assertFalse(typeSvc.findById(orgId, t.getId()).isActive());
    }

    @Test
    public void daysPerYearLabel_nullIsEmpty_numbersAreStripped() {
        LeaveTypePojo t = new LeaveTypePojo();
        assertEquals("", LeaveTypeService.daysPerYearLabel(t));

        t.setDaysPerYear(new BigDecimal("12.0"));
        assertEquals("12 days/year", LeaveTypeService.daysPerYearLabel(t));

        t.setDaysPerYear(new BigDecimal("7.5"));
        assertEquals("7.5 days/year", LeaveTypeService.daysPerYearLabel(t));

        t.setDaysPerYear(new BigDecimal("20"));
        assertEquals("20 days/year", LeaveTypeService.daysPerYearLabel(t));
        assertNull(new LeaveTypePojo().getDaysPerYear());
    }

    /* Private Helper Methods */

    private Long org(String key) {
        return orgSvc.create("Acme", key, "hr@" + key + ".test", "IN").getId();
    }
}
