package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// EmailSenderFlow -> EmailOutboxService -> EmailOutboxRepo -> DB (queued rows)
//                 -> MailService                            -> SMTP (the actual send)
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.service.EmailOutboxService;
import com.leaveapp.service.MailService;

// Transaction-boundary layer for scheduler.EmailSender: the scheduler holds no
// transaction of its own, each public method here is one proxied @Transactional unit,
// and no method here calls another on `this` -- self-invocation would bypass the
// Spring proxy and collapse the send and the fail-marking into one doomed transaction.
// Background-thread bean: no request context, no orgId/userId to authorize against --
// sending queued mail is a system action, not a user action.
@Service
public class EmailSenderFlow {

    /* Variables */

    private static final Logger log = LoggerFactory.getLogger(EmailSenderFlow.class);
    private static final int MAX_ERROR_LEN = 1000;

    @Autowired
    private EmailOutboxService outboxSvc;

    @Autowired
    private MailService mailSvc;

    /* Public Methods */

    // Sends one row and retires it to SENT, in its own transaction. Status-guarded and
    // idempotent -- a row another pass already sent is skipped, so an overlap can't
    // mail the same person twice. attempts is bumped here too, but that increment only
    // survives if the send below succeeds: mailSvc.send throwing rolls this whole
    // transaction back (including the increment), so the scheduler's catch block calls
    // markAttemptFailed in a fresh transaction to record the failed try instead.
    @Transactional(rollbackFor = Exception.class)
    public boolean sendOne(Long emailId) {
        EmailOutboxPojo email = outboxSvc.findByIdOrNull(emailId);
        if (email == null || EmailStatus.PENDING != email.getStatus()) {
            return false;
        }
        email.setAttempts(email.getAttempts() + 1);
        mailSvc.send(email.getToAddress(), email.getSubject(), email.getPlainBody(), email.getHtmlBody());
        email.setStatus(EmailStatus.SENT);
        email.setSentAt(Instant.now());
        email.setLastError(null);
        return true;
    }

    // Records a failed send in its own transaction: keeps the row PENDING for another
    // try until maxAttempts is spent, then marks it FAILED so it drops out of the poll
    // set. Called from the scheduler's catch block -- never from sendOne, per the
    // no-self-invocation rule above.
    @Transactional(rollbackFor = Exception.class)
    public void markAttemptFailed(Long emailId, Exception cause, int maxAttempts) {
        EmailOutboxPojo email = outboxSvc.findByIdOrNull(emailId);
        if (email == null || EmailStatus.PENDING != email.getStatus()) {
            return;
        }
        email.setAttempts(email.getAttempts() + 1);
        email.setLastError(truncate(cause));
        if (email.getAttempts() >= maxAttempts) {
            email.setStatus(EmailStatus.FAILED);
            log.error("email_send_failed_permanently id={} type={} attempts={}",
                emailId, email.getType(), email.getAttempts(), cause);
        } else {
            log.warn("email_send_failed_retrying id={} type={} attempts={}/{}",
                emailId, email.getType(), email.getAttempts(), maxAttempts, cause);
        }
    }

    /* Private Helper Methods */

    // The address is deliberately absent -- lastError is diagnostics, not a place to
    // leak PII.
    private static String truncate(Exception cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message.length() > MAX_ERROR_LEN ? message.substring(0, MAX_ERROR_LEN) : message;
    }
}
