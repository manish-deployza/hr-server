package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.enums.EmailType;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.pojo.PlanPojo;
import com.leaveapp.pojo.UserPojo;

// Plain unit test: the admin lookup and the outbox are hand-written doubles; the real
// MailTemplateService renders the HTML. EXITED admins are filtered out of every notice.
public class BillingMailerTest {

    /* Variables */

    private final RecordingOutbox outbox = new RecordingOutbox();
    private final StubUserService userSvc = new StubUserService();
    private final BillingMailer mailer = new BillingMailer();
    private final Instant when = Instant.parse("2030-01-02T03:04:05Z");

    {
        ReflectionTestUtils.setField(mailer, "userSvc", userSvc);
        ReflectionTestUtils.setField(mailer, "outboxSvc", outbox);
        ReflectionTestUtils.setField(mailer, "templateSvc", new MailTemplateService());
        ReflectionTestUtils.setField(mailer, "portalLink", "http://app/billing");
        userSvc.admins.add(admin(1L, "active@test.com", UserStatus.ACTIVE));
        userSvc.admins.add(admin(2L, "exited@test.com", UserStatus.DISABLED));
        userSvc.admins.add(admin(3L, "pending@test.com", UserStatus.PENDING));
    }

    /* Public Methods */

    @Test
    public void sendInvoice_queuesOneRowPerNonDisabledAdmin() {
        mailer.sendInvoice(7L, plan("Starter"), 99_900, "INR", when);

        assertEquals(List.of("active@test.com", "pending@test.com"),
            outbox.rows.stream().map(EmailOutboxPojo::getToAddress).toList());
        EmailOutboxPojo row = outbox.rows.get(0);
        assertEquals(EmailType.INVOICE, row.getType());
        assertEquals(1L, row.getUserId());
        assertEquals("Payment received -- Starter plan", row.getSubject());
        assertTrue(row.getPlainBody().contains("(INR 999.00)"));
        assertTrue(row.getPlainBody().contains("active until " + when));
        assertTrue(row.getPlainBody().endsWith("\n\nhttp://app/billing"));
        assertTrue(row.getHtmlBody().contains("http://app/billing"));
        assertTrue(row.getHtmlBody().contains("View billing"));
    }

    @Test
    public void sendInvoice_singleDigitFraction_zeroPadded() {
        mailer.sendInvoice(7L, plan("Starter"), 12_305, "INR", when);
        assertTrue(outbox.rows.get(0).getPlainBody().contains("(INR 123.05)"));
    }

    @Test
    public void sendInvoice_twoDigitFraction_notPadded() {
        mailer.sendInvoice(7L, plan("Starter"), 12_345, "INR", when);
        assertTrue(outbox.rows.get(0).getPlainBody().contains("(INR 123.45)"));
    }

    @Test
    public void sendInvoice_noAdmins_queuesNothing() {
        userSvc.admins.clear();
        mailer.sendInvoice(7L, plan("Starter"), 100, "INR", when);
        assertTrue(outbox.rows.isEmpty());
    }

    @Test
    public void sendInvoice_onlyExitedAdmins_queuesNothing() {
        userSvc.admins.clear();
        userSvc.admins.add(admin(2L, "exited@test.com", UserStatus.DISABLED));
        mailer.sendInvoice(7L, plan("Starter"), 100, "INR", when);
        assertTrue(outbox.rows.isEmpty());
    }

    @Test
    public void sendRenewalWarning_queuesWarningWithGraceDate() {
        mailer.sendRenewalWarning(7L, plan("Growth"), when);

        assertEquals(2, outbox.rows.size());
        EmailOutboxPojo row = outbox.rows.get(0);
        assertEquals(EmailType.RENEWAL_WARNING, row.getType());
        assertEquals("Payment issue -- action needed", row.getSubject());
        assertTrue(row.getPlainBody().contains("latest payment for the Growth plan"));
        assertTrue(row.getPlainBody().contains("Free plan on " + when));
    }

    @Test
    public void sendDowngradeNotice_queuesNoticeNamingOldPlan() {
        mailer.sendDowngradeNotice(7L, plan("Growth"));

        assertEquals(2, outbox.rows.size());
        EmailOutboxPojo row = outbox.rows.get(0);
        assertEquals(EmailType.DOWNGRADE_NOTICE, row.getType());
        assertEquals("Your organization has moved to the Free plan", row.getSubject());
        assertTrue(row.getPlainBody().contains("Growth subscription has ended"));
    }

    /* Private Helper Methods */

    private static PlanPojo plan(String name) {
        PlanPojo p = new PlanPojo();
        p.setName(name);
        return p;
    }

    private static UserPojo admin(Long id, String email, UserStatus status) {
        UserPojo u = new UserPojo();
        u.setId(id);
        u.setEmail(email);
        u.setStatus(status);
        return u;
    }

    /* Test Doubles */

    private static class StubUserService extends UserService {

        final List<UserPojo> admins = new ArrayList<>();

        @Override
        public List<UserPojo> findOrgAdminsByOrg(Long orgId) {
            return admins;
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
    }
}
