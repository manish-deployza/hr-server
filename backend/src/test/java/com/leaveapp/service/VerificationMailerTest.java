package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.pojo.EmpPojo;

// Plain unit test: token minting, profile lookup and the outbox are hand-written
// doubles; the real MailTemplateService renders the HTML so link/detail wiring is
// checked end to end.
public class VerificationMailerTest {

    /* Variables */

    private final RecordingOutbox outbox = new RecordingOutbox();
    private final StubEmpService empSvc = new StubEmpService();
    private final VerificationMailer mailer = new VerificationMailer();

    {
        ReflectionTestUtils.setField(mailer, "jwtSvc", new StubJwtService());
        ReflectionTestUtils.setField(mailer, "employeeSvc", empSvc);
        ReflectionTestUtils.setField(mailer, "outboxSvc", outbox);
        ReflectionTestUtils.setField(mailer, "templateSvc", new MailTemplateService());
        ReflectionTestUtils.setField(mailer, "linkBase", "http://app/verify");
    }

    /* Public Methods */

    @Test
    public void send_noEmpProfile_genericSubjectAndEmailOnlyDetails() {
        empSvc.emp = null;

        mailer.send(5L, "jane@test.com");

        EmailOutboxPojo row = outbox.only();
        assertEquals(EmailType.VERIFICATION, row.getType());
        assertEquals(5L, row.getUserId());
        assertEquals("jane@test.com", row.getToAddress());
        assertEquals("Verify your email address to activate your Leave account", row.getSubject());
        assertTrue(row.getPlainBody().contains("http://app/verify?token=verify-5"));
        assertTrue(row.getPlainBody().contains("Account details:\nEmail: jane@test.com\n"));
        assertFalse(row.getPlainBody().contains("Name:"));
        assertTrue(row.getHtmlBody().contains("http://app/verify?token=verify-5"));
    }

    @Test
    public void send_fullProfile_personalisedSubjectAndAllDetailLines() {
        empSvc.emp = emp("  Jane Doe ", "E-7", "Engineer", "Platform");

        mailer.send(5L, "jane@test.com");

        EmailOutboxPojo row = outbox.only();
        assertEquals("Jane Doe, verify your email address for Leave", row.getSubject());
        assertTrue(row.getPlainBody().contains(
            "Name: Jane Doe\nEmployee code: E-7\nDesignation: Engineer\nDepartment: Platform\nEmail: jane@test.com"));
        assertTrue(row.getHtmlBody().contains("Name: Jane Doe<br>Employee code: E-7"));
    }

    @Test
    public void send_profileWithBlankName_genericSubjectAndBlankLinesSkipped() {
        empSvc.emp = emp("   ", "", null, "Platform");

        mailer.send(5L, "jane@test.com");

        EmailOutboxPojo row = outbox.only();
        assertEquals("Verify your email address to activate your Leave account", row.getSubject());
        assertTrue(row.getPlainBody().contains("Department: Platform\nEmail: jane@test.com"));
        assertFalse(row.getPlainBody().contains("Name:"));
        assertFalse(row.getPlainBody().contains("Employee code:"));
        assertFalse(row.getPlainBody().contains("Designation:"));
    }

    @Test
    public void send_profileWithNullName_genericSubject() {
        empSvc.emp = emp(null, null, null, null);

        mailer.send(5L, "jane@test.com");

        assertEquals("Verify your email address to activate your Leave account", outbox.only().getSubject());
    }

    /* Private Helper Methods */

    private static EmpPojo emp(String name, String code, String designation, String department) {
        EmpPojo e = new EmpPojo();
        e.setStatus(EmpStatus.ACTIVE);
        e.setName(name);
        e.setEmployeeCode(code);
        e.setDesignation(designation);
        e.setDepartment(department);
        return e;
    }

    /* Test Doubles */

    private static class StubJwtService extends JwtService {

        @Override
        public String issueEmailVerify(Long userId) {
            return "verify-" + userId;
        }
    }

    private static class StubEmpService extends EmpService {

        EmpPojo emp;

        @Override
        public EmpPojo findByUserIdOrNull(Long userId) {
            return emp;
        }
    }

    private static class RecordingOutbox extends EmailOutboxService {

        final List<EmailOutboxPojo> rows = new ArrayList<>();

        @Override
        public EmailOutboxPojo enqueue(EmailType type, Long userId, String toAddress, String subject,
            String plainBody, String htmlBody) {
            EmailOutboxPojo row = new EmailOutboxPojo();
            row.setType(type);
            row.setUserId(userId);
            row.setToAddress(toAddress);
            row.setSubject(subject);
            row.setPlainBody(plainBody);
            row.setHtmlBody(htmlBody);
            rows.add(row);
            return row;
        }

        EmailOutboxPojo only() {
            assertEquals(1, rows.size());
            return rows.get(0);
        }
    }
}
