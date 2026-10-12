package com.leaveapp.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import com.leaveapp.RepositoryTestSupport;
import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.pojo.EmailOutboxPojo;

// Rows are dated year-2000 so they sort ahead of anything another test left behind.
public class EmailOutboxRepoTest extends RepositoryTestSupport {

    /* Variables */

    private static final Instant T1 = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2000-01-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2000-01-03T00:00:00Z");

    @Autowired
    private EmailOutboxRepo outboxRepo;

    /* Public Methods */

    @Test
    public void save_persists() {
        EmailOutboxPojo saved = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1));
        assertNotNull(saved.getId());
    }

    @Test
    public void findByStatusOrderByCreatedAtAsc_oldestFirstAndFiltered() {
        EmailOutboxPojo newer = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.PENDING, T2));
        EmailOutboxPojo older = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1));
        outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.SENT, T1));

        List<EmailOutboxPojo> found = outboxRepo.findByStatusOrderByCreatedAtAsc(EmailStatus.PENDING, PageRequest.of(0, 2));

        assertEquals(List.of(older.getId(), newer.getId()), ids(found));
    }

    @Test
    public void findByStatusOrderByCreatedAtAsc_respectsPageSize() {
        EmailOutboxPojo first = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.FAILED, T1));
        outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.FAILED, T2));
        outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.FAILED, T3));

        List<EmailOutboxPojo> found = outboxRepo.findByStatusOrderByCreatedAtAsc(EmailStatus.FAILED, PageRequest.of(0, 1));

        assertEquals(List.of(first.getId()), ids(found));
    }

    @Test
    public void findReapableIds_oldTerminalRowsOnly_oldestFirstBounded() {
        EmailOutboxPojo sent = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.SENT, T1));
        EmailOutboxPojo failed = outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.FAILED, T2));
        outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1));
        outboxRepo.save(EmailOutboxTestData.anEmail(EmailStatus.SENT, T3)); // at the cutoff, not before

        List<Long> found = outboxRepo.findReapableIds(EnumSet.of(EmailStatus.SENT, EmailStatus.FAILED), T3,
            PageRequest.of(0, 10));
        List<Long> first = outboxRepo.findReapableIds(EnumSet.of(EmailStatus.SENT, EmailStatus.FAILED), T3,
            PageRequest.of(0, 1));

        assertEquals(List.of(sent.getId(), failed.getId()), found);
        assertEquals(List.of(sent.getId()), first);
    }

    @Test
    public void save_nullUpdatedAt_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setUpdatedAt(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullType_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setType(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullToAddress_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setToAddress(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullSubject_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setSubject(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullPlainBody_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setPlainBody(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullHtmlBody_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setHtmlBody(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullStatus_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, T1);
        e.setStatus(null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    @Test
    public void save_nullCreatedAt_throws() {
        EmailOutboxPojo e = EmailOutboxTestData.anEmail(EmailStatus.PENDING, null);
        assertThrows(DataIntegrityViolationException.class, () -> outboxRepo.saveAndFlush(e));
    }

    /* Private Helper Methods */

    private static List<Long> ids(List<EmailOutboxPojo> rows) {
        return rows.stream().map(EmailOutboxPojo::getId).toList();
    }
}
