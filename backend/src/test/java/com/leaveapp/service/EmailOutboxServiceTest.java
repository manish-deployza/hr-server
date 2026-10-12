package com.leaveapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.repo.EmailOutboxTestData;
import com.leaveapp.repo.EmailOutboxRepo;

// Data-only service over email_outbox: enqueue stamps a PENDING row, findDue is the
// oldest-first bounded poll, findByIdOrNull is the nullable lookup, deleteTerminalBefore
// is one reaper batch.
public class EmailOutboxServiceTest extends RepositoryTestSupport {

    /* Variables */

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private EmailOutboxRepo outboxRepo;

    /* Public Methods */

    @Test
    public void enqueue_savesPendingRowWithZeroAttempts() {
        EmailOutboxPojo row = outboxSvc.enqueue(EmailType.INVITE, 9L, "a@test.com", "subj", "plain", "<p>html</p>");

        assertNotNull(row.getId());
        assertEquals(EmailStatus.PENDING, row.getStatus());
        assertEquals(0, row.getAttempts());
        assertEquals(EmailType.INVITE, row.getType());
        assertEquals(9L, row.getUserId());
        assertEquals("a@test.com", row.getToAddress());
        assertEquals("subj", row.getSubject());
        assertEquals("plain", row.getPlainBody());
        assertEquals("<p>html</p>", row.getHtmlBody());
        assertNotNull(row.getCreatedAt());
    }

    @Test
    public void findDue_returnsOnlyPendingRowsBoundedByBatchSize() {
        EmailOutboxPojo first = outboxSvc.enqueue(EmailType.INVITE, 1L, "1@test.com", "s", "p", "h");
        outboxSvc.enqueue(EmailType.INVITE, 2L, "2@test.com", "s", "p", "h");
        EmailOutboxPojo sent = outboxSvc.enqueue(EmailType.INVITE, 3L, "3@test.com", "s", "p", "h");
        sent.setStatus(EmailStatus.SENT);
        outboxRepo.saveAndFlush(sent);

        List<EmailOutboxPojo> due = outboxSvc.findDue(1000);
        assertTrue(due.stream().allMatch(e -> EmailStatus.PENDING == e.getStatus()));
        assertTrue(due.stream().noneMatch(e -> e.getId().equals(sent.getId())));
        assertTrue(due.stream().anyMatch(e -> e.getId().equals(first.getId())));

        assertEquals(1, outboxSvc.findDue(1).size());
    }

    @Test
    public void findDue_batchSizeBelowQueueDepth_returnsOldestFirst() {
        EmailOutboxPojo oldest = outboxSvc.enqueue(EmailType.INVITE, 1L, "1@test.com", "s", "p", "h");
        oldest.setCreatedAt(java.time.Instant.parse("2000-01-01T00:00:00Z"));
        outboxRepo.saveAndFlush(oldest);
        outboxSvc.enqueue(EmailType.INVITE, 2L, "2@test.com", "s", "p", "h");

        assertEquals(oldest.getId(), outboxSvc.findDue(1).get(0).getId());
    }

    @Test
    public void findByIdOrNull_existing_returnsRow() {
        EmailOutboxPojo row = outboxSvc.enqueue(EmailType.INVITE, 1L, "1@test.com", "s", "p", "h");
        assertEquals(row.getId(), outboxSvc.findByIdOrNull(row.getId()).getId());
    }

    @Test
    public void findByIdOrNull_missing_returnsNull() {
        assertNull(outboxSvc.findByIdOrNull(-1L));
    }

    @Test
    public void deleteTerminalBefore_deletesOldSentAndFailed_keepsPendingAndRecent() {
        Instant old = Instant.parse("2000-01-01T00:00:00Z");
        Instant cutoff = Instant.parse("2000-02-01T00:00:00Z");
        EmailOutboxPojo oldSent = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.SENT, old));
        EmailOutboxPojo oldFailed = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.FAILED, old));
        EmailOutboxPojo oldPending = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.PENDING, old));
        EmailOutboxPojo recentSent = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.SENT, Instant.now()));
        outboxRepo.flush();

        assertEquals(1, outboxSvc.deleteTerminalBefore(cutoff, 1)); // bounded by batch size
        assertEquals(1, outboxSvc.deleteTerminalBefore(cutoff, 10));
        assertEquals(0, outboxSvc.deleteTerminalBefore(cutoff, 10)); // drained

        assertFalse(outboxRepo.existsById(oldSent.getId())); // bulk delete: query, not the 1st-level cache
        assertFalse(outboxRepo.existsById(oldFailed.getId())); // bulk delete: query, not the 1st-level cache
        assertTrue(outboxRepo.existsById(oldPending.getId()));
        assertTrue(outboxRepo.existsById(recentSent.getId()));
    }
}
