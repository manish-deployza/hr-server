package com.leaveapp.scheduler;

// import flow: EmailOutboxReaper -> EmailOutboxService (one delete batch per transaction)
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.leaveapp.service.EmailOutboxService;

// Deletes SENT/FAILED email_outbox rows past the retention window -- EmailSender only
// stamps a terminal status, so without this the table (two TEXT bodies per row) grows
// forever. Same single-instance, no-leader-lease reasoning as EmailSender.
@Component
public class EmailOutboxReaper {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxReaper.class);

    @Autowired
    private EmailOutboxService outboxSvc;

    @Value("${app.mail.reaper.batch-size}")
    private int batchSize;

    @Value("${app.mail.reaper.retention-days}")
    private int retentionDays;

    /* Public Methods */

    @Scheduled(fixedDelayString = "${app.mail.reaper.poll-delay-ms}")
    public void run() {
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int total = 0;
            int deleted;
            do {
                deleted = outboxSvc.deleteTerminalBefore(cutoff, batchSize);
                total += deleted;
            } while (deleted == batchSize);
            if (total > 0) {
                log.info("email outbox reaped rows={} cutoff={}", total, cutoff);
            }
        } catch (Exception e) {
            log.error("email outbox reaper error", e);
        }
    }
}
