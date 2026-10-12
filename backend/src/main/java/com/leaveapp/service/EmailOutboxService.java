package com.leaveapp.service;

// import flow (layering: Service -> Repo -> DB): EmailOutboxService -> EmailOutboxRepo -> DB
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmailOutboxPojo;
import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.model.enums.EmailType;
import com.leaveapp.repo.EmailOutboxRepo;

// Data-only: no send logic, no retry/threshold decisions -- those live in
// EmailSenderFlow. This class just reads and writes email_outbox rows.
@Service
public class EmailOutboxService {

    /* Variables */

    @Autowired
    private EmailOutboxRepo outboxRepo;

    /* Public Methods */

    // Called by the 4 *Mailer services instead of sending directly -- see
    // EmailSenderFlow's class comment for why the actual send happens later.
    @Transactional(rollbackFor = Exception.class)
    public EmailOutboxPojo enqueue(EmailType type, Long userId, String toAddress, String subject,
        String plainBody, String htmlBody) {
        EmailOutboxPojo row = new EmailOutboxPojo();
        Instant now = Instant.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setType(type);
        row.setUserId(userId);
        row.setToAddress(toAddress);
        row.setSubject(subject);
        row.setPlainBody(plainBody);
        row.setHtmlBody(htmlBody);
        row.setStatus(EmailStatus.PENDING);
        row.setAttempts(0);
        return outboxRepo.save(row);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<EmailOutboxPojo> findDue(int batchSize) {
        return outboxRepo.findByStatusOrderByCreatedAtAsc(EmailStatus.PENDING, PageRequest.of(0, batchSize));
    }

    // One reaper batch in its own transaction: deletes up to batchSize SENT/FAILED rows
    // created before cutoff and returns how many it deleted (< batchSize = drained).
    @Transactional(rollbackFor = Exception.class)
    public int deleteTerminalBefore(Instant cutoff, int batchSize) {
        List<Long> ids = outboxRepo.findReapableIds(EnumSet.of(EmailStatus.SENT, EmailStatus.FAILED), cutoff,
            PageRequest.of(0, batchSize));
        if (!ids.isEmpty()) {
            outboxRepo.deleteAllByIdInBatch(ids);
        }
        return ids.size();
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public EmailOutboxPojo findByIdOrNull(Long id) {
        return outboxRepo.findById(id).orElse(null);
    }
}
