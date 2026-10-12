package com.leaveapp.pojo;

import java.time.Instant;

import com.leaveapp.model.enums.EmailStatus;
import com.leaveapp.model.enums.EmailType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One queued outbound email. A *Flow must not call MailService directly inside its
// own transaction -- it enqueues a row here instead (see the 4 *Mailer services), and
// EmailSenderFlow/scheduler.EmailSender send it later, outside any request's
// transaction. plainBody/htmlBody are the fully-rendered message at enqueue time, not
// a template -- the mailers already build the whole body in Java.
@Entity
// idx_email_outbox_status_created backs the sender's poll and the cleanup job's
// (status, created_at) scans.
@Table(
    name = "email_outbox",
    indexes = @Index(name = "idx_email_outbox_status_created", columnList = "status, created_at")
)
@Getter
@Setter
@NoArgsConstructor
public class EmailOutboxPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // status/attempts/sentAt/lastError change after insert
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "email_outbox_gen")
    @TableGenerator(
        name = "email_outbox_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "email_outbox_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmailType type;

    // recipient's account, for log correlation only -- not a join target
    @Column(name = "user_id", nullable = true)
    private Long userId;

    @Column(name = "to_address", nullable = false, length = 255)
    private String toAddress;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "plain_body", nullable = false, columnDefinition = "TEXT")
    private String plainBody;

    @Column(name = "html_body", nullable = false, columnDefinition = "TEXT")
    private String htmlBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EmailStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", nullable = true, length = 1000)
    private String lastError;

    @Column(name = "sent_at", nullable = true)
    private Instant sentAt;

    /* Protected Methods */

    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
