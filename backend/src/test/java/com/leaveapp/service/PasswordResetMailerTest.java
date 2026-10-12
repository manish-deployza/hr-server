package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
// doubles; the real MailTemplateService renders the HTML.
public class PasswordResetMailerTest {

    /* Variables */

    private final RecordingOutbox outbox = new RecordingOutbox();
    private final StubEmpService empSvc = new StubEmpService();
    private final PasswordResetMailer mailer = new PasswordResetMailer();

    {
        ReflectionTestUtils.setField(mailer, "jwtSvc", new StubJwtService());
        ReflectionTestUtils.setField(mailer, "employeeSvc", empSvc);
        ReflectionTestUtils.setField(mailer, "outboxSvc", outbox);
        ReflectionTestUtils.setField(mailer, "templateSvc", new MailTemplateService());
        ReflectionTestUtils.setField(mailer, "linkBase", "http://app/reset");
    }

    /* Public Methods */

    @Test
    public void send_noEmpProfile_genericSubjectAndLinkInBothBodies() {
        empSvc.emp = null;

        mailer.send(5L, "jane@test.com");

        EmailOutboxPojo row = outbox.only();
        assertEquals(EmailType.PASSWORD_RESET, row.getType());
        assertEquals(5L, row.getUserId());
        assertEquals("jane@test.com", row.getToAddress());
        assertEquals("Reset your Leave account password", row.getSubject());
        assertTrue(row.getPlainBody().contains("http://app/reset?token=reset-5"));
        assertTrue(row.getHtmlBody().contains("http://app/reset?token=reset-5"));
    }

    @Test
    public void send_namedProfile_personalisedTrimmedSubject() {
        empSvc.emp = emp("  Jane Doe ");

        mailer.send(5L, "jane@test.com");

        assertEquals("Jane Doe, reset your Leave account password", outbox.only().getSubject());
    }

    @Test
    public void send_profileWithBlankName_genericSubject() {
        empSvc.emp = emp("  ");

        mailer.send(5L, "jane@test.com");

        assertEquals("Reset your Leave account password", outbox.only().getSubject());
    }

    @Test
    public void send_profileWithNullName_genericSubject() {
        empSvc.emp = emp(null);

        mailer.send(5L, "jane@test.com");

        assertEquals("Reset your Leave account password", outbox.only().getSubject());
    }

    /* Private Helper Methods */

    private static EmpPojo emp(String name) {
        EmpPojo e = new EmpPojo();
        e.setStatus(EmpStatus.ACTIVE);
        e.setName(name);
        return e;
    }

    /* Test Doubles */

    private static class StubJwtService extends JwtService {

        @Override
        public String issuePasswordReset(Long userId) {
            return "reset-" + userId;
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
