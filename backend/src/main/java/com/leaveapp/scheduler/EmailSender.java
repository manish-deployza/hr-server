package com.leaveapp.scheduler;

// import flow: EmailSender -> EmailOutboxService (what's due) -> EmailSenderFlow (per-row transaction)
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.service.EmailOutboxService;
import com.leaveapp.workflow.EmailSenderFlow;

// Polls the outbox and drives EmailSenderFlow's per-row transactions. Holds no
// transaction of its own -- see EmailSenderFlow's class comment for why each step
// must stay a separate proxied call. Single-instance deployment (one WAR, one Tomcat
// -- see CLAUDE.md), so no leader-lease/lock: only one process is ever polling, unlike
// a clustered deployment where two instances could double-send the same row.
@Component
public class EmailSender {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private EmailSenderFlow senderFlow;

    @Value("${app.mail.batch-size}")
    private int batchSize;

    @Value("${app.mail.max-attempts}")
    private int maxAttempts;

    /* Public Methods */

    @Scheduled(fixedDelayString = "${app.mail.poll-delay-ms}")
    public void run() {
        List<EmailOutboxPojo> due = outboxSvc.findDue(batchSize);
        for (EmailOutboxPojo email : due) {
            Long id = email.getId();
            try {
                senderFlow.sendOne(id);
            } catch (Exception e) {
                senderFlow.markAttemptFailed(id, e, maxAttempts);
            }
        }
    }
}
