package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.service.EmailOutboxService;
import com.leaveapp.service.MailService;

// Plain unit test: the transaction boundaries are not under test here (they need the
// Spring proxy), only the status guards, attempt accounting and error truncation. The
// outbox lookup and the SMTP hop are hand-written doubles.
public class EmailSenderFlowTest {

    /* Variables */

    private final StubOutbox outbox = new StubOutbox();
    private final RecordingMailService mail = new RecordingMailService();
    private final EmailSenderFlow flow = new EmailSenderFlow();

    {
        ReflectionTestUtils.setField(flow, "outboxSvc", outbox);
        ReflectionTestUtils.setField(flow, "mailSvc", mail);
    }

    /* Public Methods */

    @Test
    public void sendOne_missingRow_returnsFalseAndSendsNothing() {
        assertFalse(flow.sendOne(99L));
        assertTrue(mail.sent.isEmpty());
    }

    @Test
    public void sendOne_alreadySentRow_returnsFalseAndSendsNothing() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.SENT, 1);

        assertFalse(flow.sendOne(1L));

        assertTrue(mail.sent.isEmpty());
        assertEquals(1, row.getAttempts());
    }

    @Test
    public void sendOne_failedRow_returnsFalseAndSendsNothing() {
        outbox.put(1L, EmailStatus.FAILED, 5);
        assertFalse(flow.sendOne(1L));
        assertTrue(mail.sent.isEmpty());
    }

    @Test
    public void sendOne_pendingRow_sendsAndRetiresToSent() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 2);
        row.setLastError("earlier failure");

        assertTrue(flow.sendOne(1L));

        assertEquals(List.of("to1@test.com|subj|plain|<p>html</p>"), mail.sent);
        assertEquals(EmailStatus.SENT, row.getStatus());
        assertEquals(3, row.getAttempts());
        assertNotNull(row.getSentAt());
        assertNull(row.getLastError());
    }

    @Test
    public void sendOne_smtpFailure_propagatesAndLeavesRowPending() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 0);
        mail.failure = new MailSendException("relay down");

        assertThrows(MailSendException.class, () -> flow.sendOne(1L));

        assertEquals(EmailStatus.PENDING, row.getStatus());
        assertNull(row.getSentAt());
    }

    @Test
    public void markAttemptFailed_missingRow_isNoop() {
        flow.markAttemptFailed(99L, new RuntimeException("x"), 5);
    }

    @Test
    public void markAttemptFailed_nonPendingRow_isNoop() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.SENT, 1);

        flow.markAttemptFailed(1L, new RuntimeException("x"), 5);

        assertEquals(EmailStatus.SENT, row.getStatus());
        assertEquals(1, row.getAttempts());
        assertNull(row.getLastError());
    }

    @Test
    public void markAttemptFailed_belowMax_staysPendingWithRecordedError() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 0);

        flow.markAttemptFailed(1L, new IllegalStateException("smtp refused"), 5);

        assertEquals(EmailStatus.PENDING, row.getStatus());
        assertEquals(1, row.getAttempts());
        assertEquals("IllegalStateException: smtp refused", row.getLastError());
    }

    @Test
    public void markAttemptFailed_reachesMax_marksFailed() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 4);

        flow.markAttemptFailed(1L, new RuntimeException("smtp refused"), 5);

        assertEquals(EmailStatus.FAILED, row.getStatus());
        assertEquals(5, row.getAttempts());
    }

    @Test
    public void markAttemptFailed_pastMax_marksFailed() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 9);

        flow.markAttemptFailed(1L, new RuntimeException("smtp refused"), 5);

        assertEquals(EmailStatus.FAILED, row.getStatus());
    }

    @Test
    public void markAttemptFailed_nullCauseMessage_recordsLiteralNull() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 0);

        flow.markAttemptFailed(1L, new RuntimeException(), 5);

        assertEquals("RuntimeException: null", row.getLastError());
    }

    @Test
    public void markAttemptFailed_longMessage_truncatedTo1000Chars() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 0);

        flow.markAttemptFailed(1L, new RuntimeException("x".repeat(5000)), 5);

        assertEquals(1000, row.getLastError().length());
        assertTrue(row.getLastError().startsWith("RuntimeException: xxx"));
    }

    @Test
    public void markAttemptFailed_messageExactly1000Chars_keptWhole() {
        EmailOutboxPojo row = outbox.put(1L, EmailStatus.PENDING, 0);
        String prefix = "RuntimeException: ";

        flow.markAttemptFailed(1L, new RuntimeException("y".repeat(1000 - prefix.length())), 5);

        assertEquals(1000, row.getLastError().length());
    }

    /* Test Doubles */

    private static class StubOutbox extends EmailOutboxService {

        private final Map<Long, EmailOutboxPojo> rows = new HashMap<>();

        EmailOutboxPojo put(Long id, EmailStatus status, int attempts) {
            EmailOutboxPojo row = new EmailOutboxPojo();
            row.setId(id);
            row.setType(EmailType.INVITE);
            row.setToAddress("to" + id + "@test.com");
            row.setSubject("subj");
            row.setPlainBody("plain");
            row.setHtmlBody("<p>html</p>");
            row.setStatus(status);
            row.setAttempts(attempts);
            rows.put(id, row);
            return row;
        }

        @Override
        public EmailOutboxPojo findByIdOrNull(Long id) {
            return rows.get(id);
        }
    }

    private static class RecordingMailService extends MailService {

        final List<String> sent = new ArrayList<>();
        RuntimeException failure;

        RecordingMailService() {
            super("localhost", 2525, "u", "p");
        }

        @Override
        public void send(String to, String subject, String plainText, String html) {
            if (failure != null) {
                throw failure;
            }
            sent.add(to + "|" + subject + "|" + plainText + "|" + html);
        }
    }
}
