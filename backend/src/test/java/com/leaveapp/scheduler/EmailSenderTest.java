package com.leaveapp.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.service.EmailOutboxService;
import com.leaveapp.workflow.EmailSenderFlow;

// Plain unit test: one poll asks the outbox for batch-size rows, sends each in its own
// call, and a failing row is recorded with its cause and the configured max attempts
// without stopping the rest of the batch.
public class EmailSenderTest {

    /* Variables */

    private final List<String> log = new ArrayList<>();
    private final RecordingOutbox outbox = new RecordingOutbox(log);
    private final RecordingFlow flow = new RecordingFlow(log);
    private final EmailSender sender = new EmailSender();

    {
        ReflectionTestUtils.setField(sender, "outboxSvc", outbox);
        ReflectionTestUtils.setField(sender, "senderFlow", flow);
        ReflectionTestUtils.setField(sender, "batchSize", 25);
        ReflectionTestUtils.setField(sender, "maxAttempts", 5);
    }

    /* Public Methods */

    @Test
    public void run_nothingDue_noSends() {
        sender.run();
        assertEquals(List.of("findDue:25"), log);
    }

    @Test
    public void run_dueRows_sentInOrder() {
        outbox.due = List.of(row(1L), row(2L), row(3L));

        sender.run();

        assertEquals(List.of("findDue:25", "send:1", "send:2", "send:3"), log);
    }

    @Test
    public void run_oneFails_markedFailedAndRestStillSent() {
        outbox.due = List.of(row(1L), row(2L), row(3L));
        flow.failFor = Set.of(2L);

        sender.run();

        assertEquals(List.of("findDue:25", "send:1", "send:2", "failed:2:5", "send:3"), log);
        assertSame(flow.failure, flow.lastCause);
    }

    @Test
    public void run_allFail_eachMarkedFailed() {
        outbox.due = List.of(row(1L), row(2L));
        flow.failFor = Set.of(1L, 2L);

        sender.run();

        assertEquals(List.of("findDue:25", "send:1", "failed:1:5", "send:2", "failed:2:5"), log);
    }

    @Test
    public void run_markingFailedItselfThrows_propagates() {
        outbox.due = List.of(row(1L));
        flow.failFor = Set.of(1L);
        flow.failMarking = true;

        assertThrows(IllegalStateException.class, sender::run);
        assertTrue(log.contains("failed:1:5"));
    }

    @Test
    public void run_usesConfiguredBatchSizeAndMaxAttempts() {
        ReflectionTestUtils.setField(sender, "batchSize", 3);
        ReflectionTestUtils.setField(sender, "maxAttempts", 9);
        outbox.due = List.of(row(7L));
        flow.failFor = Set.of(7L);

        sender.run();

        assertEquals(List.of("findDue:3", "send:7", "failed:7:9"), log);
    }

    /* Private Helper Methods */

    private static EmailOutboxPojo row(Long id) {
        EmailOutboxPojo e = new EmailOutboxPojo();
        e.setId(id);
        return e;
    }

    /* Test Doubles */

    private static final class RecordingOutbox extends EmailOutboxService {
        private final List<String> log;
        private List<EmailOutboxPojo> due = List.of();

        private RecordingOutbox(List<String> log) {
            this.log = log;
        }

        @Override
        public List<EmailOutboxPojo> findDue(int batchSize) {
            log.add("findDue:" + batchSize);
            return due;
        }
    }

    private static final class RecordingFlow extends EmailSenderFlow {
        private final List<String> log;
        private final RuntimeException failure = new RuntimeException("smtp down");
        private Set<Long> failFor = Set.of();
        private boolean failMarking;
        private Exception lastCause;

        private RecordingFlow(List<String> log) {
            this.log = log;
        }

        @Override
        public boolean sendOne(Long emailId) {
            log.add("send:" + emailId);
            if (failFor.contains(emailId)) {
                throw failure;
            }
            return true;
        }

        @Override
        public void markAttemptFailed(Long emailId, Exception cause, int maxAttempts) {
            log.add("failed:" + emailId + ":" + maxAttempts);
            lastCause = cause;
            if (failMarking) {
                throw new IllegalStateException("db down");
            }
        }
    }
}
