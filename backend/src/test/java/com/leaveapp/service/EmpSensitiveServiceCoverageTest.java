package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.model.enums.BloodGroup;
import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.exception.BadRequestException;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.pojo.AddressPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.OrgPojo;
import com.leaveapp.pojo.UserPojo;

// Gap-filler for EmpSensitiveServiceTest: every null-means-unchanged arm of the
// apply* methods, the length boundaries, and the lookups.
public class EmpSensitiveServiceCoverageTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmpSensitiveService sensSvc;

    @Autowired
    private UserService userSvc;

    @Autowired
    private OrgService orgSvc;

    /* Public Methods */

    @Test
    public void findByUserIdOrNull_unknownId_returnsNull() {
        assertNull(sensSvc.findByUserIdOrNull(987654L));
    }

    @Test
    public void findByUserIds_returnsMapKeyedByUserId() {
        EmpSensitivePojo a = aSens("acme-sensc-1", "a@acme.test");
        EmpSensitivePojo b = aSens("acme-sensc-2", "b@acme.test");

        Map<Long, EmpSensitivePojo> found = sensSvc.findByUserIds(List.of(a.getUserId(), b.getUserId(), 987654L));

        assertEquals(2, found.size());
        assertEquals(a, found.get(a.getUserId()));
        assertEquals(b, found.get(b.getUserId()));
    }

    @Test
    public void applyContactFields_exactlyAtLimits_isAccepted() {
        EmpSensitivePojo sens = aSens("acme-sensc-3", "c@acme.test");

        sensSvc.applyContactFields(sens, "1".repeat(20), "x".repeat(255));

        assertEquals(20, sens.getPhone().length());
        assertEquals(255, sens.getContactAddress().length());
    }

    @Test
    public void applyContactFields_whitespaceOnlyOverLimitIsTrimmedFirst() {
        EmpSensitivePojo sens = aSens("acme-sensc-4", "d@acme.test");

        sensSvc.applyContactFields(sens, " ".repeat(30), null); // trims to "" before the length check

        assertEquals("", sens.getPhone());
    }

    @Test
    public void applyContactFields_bothNull_changesNothing() {
        EmpSensitivePojo sens = aSens("acme-sensc-5", "e@acme.test");

        sensSvc.applyContactFields(sens, null, null);

        assertNull(sens.getPhone());
        assertNull(sens.getContactAddress());
    }

    @Test
    public void applyPersonalFields_allFieldsSent_areAllWritten() {
        EmpSensitivePojo sens = aSens("acme-sensc-6", "f@acme.test");

        sensSvc.applyPersonalFields(sens, LocalDate.of(1990, 1, 2), "F", "IN", "Single", "O+", "me@home.test");

        assertEquals(LocalDate.of(1990, 1, 2), sens.getDateOfBirth());
        assertEquals("F", sens.getGender());
        assertEquals("IN", sens.getNationality());
        assertEquals("Single", sens.getMaritalStatus());
        assertEquals(BloodGroup.O_POS, sens.getBloodGroup());
        assertEquals("me@home.test", sens.getPersonalEmail());
    }

    @Test
    public void applyPersonalFields_allNull_leavesExistingValues() {
        EmpSensitivePojo sens = aSens("acme-sensc-7", "g@acme.test");
        sensSvc.applyPersonalFields(sens, LocalDate.of(1990, 1, 2), "F", "IN", "Single", "O+", "me@home.test");

        sensSvc.applyPersonalFields(sens, null, null, null, null, null, null);

        assertEquals(LocalDate.of(1990, 1, 2), sens.getDateOfBirth());
        assertEquals("F", sens.getGender());
        assertEquals("IN", sens.getNationality());
        assertEquals("Single", sens.getMaritalStatus());
        assertEquals(BloodGroup.O_POS, sens.getBloodGroup());
        assertEquals("me@home.test", sens.getPersonalEmail());
    }

    @Test
    public void applyPersonalFields_onlyOneFieldSent_othersStayNull() {
        EmpSensitivePojo dob = aSens("acme-sensc-8", "h@acme.test");
        sensSvc.applyPersonalFields(dob, LocalDate.of(2000, 5, 5), null, null, null, null, null);
        assertEquals(LocalDate.of(2000, 5, 5), dob.getDateOfBirth());
        assertNull(dob.getGender());

        EmpSensitivePojo nat = aSens("acme-sensc-9", "i@acme.test");
        sensSvc.applyPersonalFields(nat, null, null, "US", null, null, null);
        assertEquals("US", nat.getNationality());
        assertNull(nat.getDateOfBirth());
    }

    @Test
    public void applyAddressFields_allFieldsSent_areAllWritten() {
        EmpSensitivePojo sens = aSens("acme-sensc-10", "j@acme.test");
        AddressPojo in = new AddressPojo();
        in.setAddress1("a1");
        in.setAddress2("a2");
        in.setCity("city");
        in.setLandmark("mark");
        in.setPostalCode("560001");
        in.setState("KA");
        in.setCountry("IN");

        sensSvc.applyAddressFields(sens, in, "Em", "Mother", "+91 90000 00000");

        assertEquals("a1", sens.getAddress().getAddress1());
        assertEquals("a2", sens.getAddress().getAddress2());
        assertEquals("city", sens.getAddress().getCity());
        assertEquals("mark", sens.getAddress().getLandmark());
        assertEquals("560001", sens.getAddress().getPostalCode());
        assertEquals("KA", sens.getAddress().getState());
        assertEquals("IN", sens.getAddress().getCountry());
        assertEquals("Em", sens.getEmergencyName());
        assertEquals("Mother", sens.getEmergencyRelationship());
        assertEquals("+91 90000 00000", sens.getEmergencyPhone());
    }

    @Test
    public void applyAddressFields_emptyIncoming_leavesExistingValues() {
        EmpSensitivePojo sens = aSens("acme-sensc-11", "k@acme.test");
        AddressPojo in = new AddressPojo();
        in.setCity("Pune");
        sensSvc.applyAddressFields(sens, in, "Em", "Mother", "+91 90000 00000");

        sensSvc.applyAddressFields(sens, new AddressPojo(), null, null, null);

        assertEquals("Pune", sens.getAddress().getCity());
        assertNull(sens.getAddress().getAddress1());
        assertEquals("Em", sens.getEmergencyName());
        assertEquals("Mother", sens.getEmergencyRelationship());
        assertEquals("+91 90000 00000", sens.getEmergencyPhone());
    }

    @Test
    public void applyPayFields_allFieldsSent_areAllWritten() {
        EmpSensitivePojo sens = aSens("acme-sensc-12", "l@acme.test");

        sensSvc.applyPayFields(sens, "123456789", "HDFC0001234", "NID123", "TAX123", "P1234567");

        assertEquals("123456789", sens.getBankAccount());
        assertEquals("HDFC0001234", sens.getBankIfsc());
        assertEquals("NID123", sens.getNationalId());
        assertEquals("TAX123", sens.getTaxId());
        assertEquals("P1234567", sens.getPassport());
    }

    @Test
    public void applyPayFields_allNull_leavesExistingValues() {
        EmpSensitivePojo sens = aSens("acme-sensc-13", "m@acme.test");
        sensSvc.applyPayFields(sens, "123456789", "HDFC0001234", "NID123", "TAX123", "P1234567");

        sensSvc.applyPayFields(sens, null, null, null, null, null);

        assertEquals("123456789", sens.getBankAccount());
        assertEquals("HDFC0001234", sens.getBankIfsc());
        assertEquals("NID123", sens.getNationalId());
        assertEquals("TAX123", sens.getTaxId());
        assertEquals("P1234567", sens.getPassport());
    }

    @Test
    public void applyPayFields_onlyOneFieldSent_othersStayNull() {
        EmpSensitivePojo bank = aSens("acme-sensc-14", "n@acme.test");
        sensSvc.applyPayFields(bank, "123456789", null, null, null, null);
        assertEquals("123456789", bank.getBankAccount());
        assertNull(bank.getBankIfsc());

        EmpSensitivePojo ifsc = aSens("acme-sensc-15", "o@acme.test");
        sensSvc.applyPayFields(ifsc, null, "HDFC0001234", null, null, null);
        assertEquals("HDFC0001234", ifsc.getBankIfsc());
        assertNull(ifsc.getBankAccount());
    }

    /* Private Helper Methods */

    private EmpSensitivePojo aSens(String orgKey, String email) {
        OrgPojo org = orgSvc.create("Acme", orgKey, "hr@acme.test", "IN");
        UserPojo user = userSvc.createUser(org.getId(), email, pwd("s3cret!!"), UserRole.EMPLOYEE);
        return sensSvc.createEmpty(user.getId());
    }

    private static byte[] pwd(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8);
    }
}
